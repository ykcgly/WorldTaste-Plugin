package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.behavior.Behaviors.CropCfg;
import com.haiman233.worldtaste.behavior.Behaviors.CropDrop;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 作物方块（machines.yml 中 script 为 seed/* 的物品）。
 *
 * <p>生长进度完全采用原版机制：种子放置后由 tick 转为作物材质并归零 age，其后由原版随机刻
 * 自然生长（骨粉催熟同样生效）；成熟与否在破坏时按原版 age 是否满档判定，插件不再按时间
 * 推进生长阶段。</p>
 *
 * <p>唯一例外是紫颂花（CHORUS_FLOWER）：原版紫颂只会"移动式"生长——每次生长把原位变成梗、
 * 在新格生成 age+1 的花，而本插件为保护收获流程会删除上格延伸并把基座还原为花，原版机制
 * 无法让紫颂花原地加龄。故对紫颂作物保留按 growMs 的定时推进（含成熟标记持久化与内存缓存，
 * 区块卸载时兜底清理）。</p>
 */
public class CropBlock extends SlimefunItem {

    /** 紫颂定时生长的阶段比例表（仅 CHORUS_FLOWER 材质使用）。 */
    private static final double[] SMALL_STEPS = {1/10d, 1/6d, 1/3d, 0.5, 2/3d, 5/6d, 1d, 7/6d};
    /** 水平四向（方向性作物附着扫描用）。 */
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    /** 紫颂定时生长的持久化键（BlockStorage，随 Slimefun 数据库落盘）：生长起点时间戳 / 成熟标记。 */
    private static final String KEY_START = "wt-crop-start";
    private static final String KEY_GROWN = "wt-crop-grown";

    private final CropCfg cfg;
    /** 预算的生长阈值 growMs*SMALL_STEPS[i]（不变量，double 保精确语义）。构造期一次计算，仅紫颂作物使用。 */
    private final double[] growMsSteps;
    /** 紫颂定时生长的内存状态（按位置全局共享；仅 CHORUS_FLOWER 材质读写，普通作物零状态，
     *  age 即全部生长状态）。条目随破坏/重种/方块移除清除，并在区块卸载时兜底清理
     *  （{@link ChorusStateCleanup}），防止被外部手段移除的作物永久残留。tick 与破坏事件均在
     *  主线程，无需并发容器。 */
    private static final Map<Location, Long> lastUse = new HashMap<>();
    private static final Set<Location> grown = new HashSet<>();
    /** 方块当前阶段缓存：仅阶段变化时才写方块，避免每 tick getState()/setBlockData() 的对象开销（spark 热点优化）。 */
    private static final Map<Location, Integer> stage = new HashMap<>();
    /** 紫颂上方清理节拍：每 2 tick 一次（原版随机刻频率远低于此，节流不影响拦截效果）。 */
    private int chorusCheck;
    /** 紫颂外部催熟检测节拍：每 8 tick 一次。 */
    private int matureCheck;
    /** 原版生长年龄上限压回值：仅当配置 maxAge 低于原版上限时 ≥0（如瓜茎 maxAge=6 < 原版 7）。
     *  此类作物长到该年龄后由 {@code CropListener} 取消原版生长事件（随机刻/骨粉均走 BlockGrowEvent），
     *  使其停留在成熟阶段、不再触发原版满龄行为（茎结果实）；
     *  -1 表示无限制（绝大多数作物 maxAge=原版上限，零额外开销）。 */
    private final int clampMax;
    /** 配置 maxAge 低于原版上限的作物材质集合（BlockGrowEvent 拦截的材质级预筛）。
     *  加载期单线程填充、运行期只读，无需并发容器。 */
    private static final java.util.Set<Material> CLAMPED_MATERIALS = java.util.EnumSet.noneOf(Material.class);
    /** 作物方块是否带方向（如 COCOA 需附着在原木侧面），构造期由材质 BlockData 判定。 */
    private final boolean directionalCrop;

    public CropBlock(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, CropCfg cfg) {
        super(group, item, rt, recipe);
        this.cfg = cfg;
        this.growMsSteps = new double[SMALL_STEPS.length];
        for (int i = 0; i < SMALL_STEPS.length; i++) growMsSteps[i] = cfg.growMs * SMALL_STEPS[i];
        int computedClamp = -1;
        boolean directional = false;
        try {
            org.bukkit.block.data.BlockData data = cfg.material.createBlockData();
            directional = data instanceof Directional;
            if (data instanceof Ageable a) {
                if (cfg.maxAge < a.getMaximumAge()) computedClamp = Math.max(0, cfg.maxAge);
            } else {
                // 原版生长要求材质可加龄（Ageable）：非 Ageable 材质的作物永远无法成熟，加载期给出告警
                com.haiman233.worldtaste.WT.log("作物 " + getId() + " 的材质 " + cfg.material
                        + " 不支持原版生长（非 Ageable），该作物将无法成熟");
            }
        } catch (Throwable ignored) {
            // 异常材质（如 air）交由运行期 type 守卫兜底
        }
        this.clampMax = computedClamp;
        this.directionalCrop = directional;
        if (computedClamp >= 0) {
            CLAMPED_MATERIALS.add(cfg.material);
        }
    }

    /** 是否为紫颂作物（唯一需要定时推进生长的材质）。 */
    private boolean isChorus() {
        return cfg.material == Material.CHORUS_FLOWER;
    }

    /** 作物方块是否带方向（如 COCOA 需附着在原木侧面）。 */
    public boolean isDirectionalCrop() {
        return directionalCrop;
    }

    /** 原版生长年龄上限（-1=无限制）；{@code CropListener} 据此在 BlockGrowEvent 里取消越限生长。 */
    public int getClampMax() {
        return clampMax;
    }

    /** 材质级预筛：该材质是否存在限高作物（BlockGrowEvent 高频路径的第一道廉价过滤）。 */
    public static boolean isClampedMaterial(Material m) {
        return CLAMPED_MATERIALS.contains(m);
    }

    /** 种植要求：显式 plantOn 优先，否则按材质推断（原版机制）；null 表示不限制。 */
    public List<Material> getPlantOn() {
        return cfg.resolvedPlantOn();
    }

    /** 不由 Slimefun 框架掉落种子本身（仅由 CropListener 在成熟时掉落作物/种子，对齐原脚本）。 */
    @Override
    public List<ItemStack> getDrops() {
        return java.util.Collections.emptyList();
    }

    @Override
    public void preRegister() {
        super.preRegister();
        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }
            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                CropBlock.this.tick(b);
            }
        });
    }

    private void tick(Block b) {
        Material type = b.getType();
        boolean isSeedHead = (type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD);
        if (type != cfg.material && !isSeedHead) {
            // 仍登记为我们的作物（玩家未破坏）时，可能是原版机制替换了方块
            // （如紫颂随机生长/甘蔗物理变化）：恢复作物材质继续生长，避免误注销；
            // 否则视为被移除（耕地破坏、爆炸、踩踏等），清理状态并注销，
            // 避免幽灵 tick 把 AIR 设回作物刷原版种子。
            if (BlockStorage.hasBlockInfo(b)) {
                // 恢复材质后按周边支撑修正方向性作物的朝向；若在该位置已无法存活
                //（支撑被移走等），按非玩家破坏处理并清数据，避免复活 → 破坏死循环
                b.setType(cfg.material);
                applyFacing(b, null);
                if (!b.getBlockData().isSupported(b)) {
                    discardUnsupportable(b);
                    return;
                }
            } else {
                purge(b.getLocation());
                BlockStorage.clearBlockInfo(b);
                return;
            }
        }
        if (isSeedHead) {
            // 刚种下/重放：转为作物材质并归零 age，其后交给原版随机刻生长
            if (isChorus()) {
                // 重置紫颂定时状态（防同位置重放秒熟）
                Location l = b.getLocation();
                grown.remove(l);
                lastUse.remove(l);
                stage.remove(l);
                BlockStorage.addBlockInfo(b, KEY_START, null);
                BlockStorage.addBlockInfo(b, KEY_GROWN, null);
            }
            setStage(b, 0);
            return;
        }
        if (isChorus()) {
            tickChorus(b);
            return;
        }
        // 普通作物：生长完全交给原版随机刻（含骨粉催熟），无 tick 逻辑。
        // maxAge 低于原版上限的限高作物（如瓜茎）由 CropListener 的 BlockGrowEvent 拦截在
        // 成熟年龄上，无需 tick 轮询回退生长进度。
    }

    /** 紫颂作物定时生长（原版无法让紫颂花原地加龄，见类注释）。 */
    private void tickChorus(Block b) {
        // 阻止原版紫颂类随机生长：CHORUS_FLOWER 种在末地石上会被原版随机刻
        // 在上方长出 CHORUS_PLANT（紫颂树），替换/延伸作物并破坏 Slimefun 收获流程；
        // 节流后（每 2 tick）仍远快于原版随机刻频率，紫颂生长完全由本插件控制。
        if ((++chorusCheck & 1) == 0) {
            Block above = b.getRelative(BlockFace.UP);
            Material upType = above.getType();
            if (upType == Material.CHORUS_PLANT || upType == Material.CHORUS_FLOWER) {
                above.setType(Material.AIR);
            }
        }
        Location l = b.getLocation();
        if (grown.contains(l)) return;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(l);
        if (last == null) {
            // 内存状态缺失（重启/新放置）：从持久化恢复；成熟标记优先，避免重新计时
            if (BlockStorage.getLocationInfo(l, KEY_GROWN) != null) {
                grown.add(l);
                return;
            }
            String saved = BlockStorage.getLocationInfo(l, KEY_START);
            if (saved != null) {
                try { last = Long.parseLong(saved); } catch (NumberFormatException ignored) { last = null; }
            }
            if (last == null) {
                lastUse.put(l, now);
                BlockStorage.addBlockInfo(b, KEY_START, String.valueOf(now));
                stage.remove(l);
                setStage(b, 0);
                return;
            }
            lastUse.put(l, last);
        }
        // 外部催熟兜底（节流每 8 tick；骨粉等常规路径破坏时按 age 判定）：
        // 原版 age 已达最大则补记成熟并持久化
        if ((++matureCheck & 7) == 0 && isNaturallyMature(b)) {
            grown.add(l);
            BlockStorage.addBlockInfo(b, KEY_GROWN, "1");
            return;
        }
        long elapsed = now - last;
        int target = cfg.maxAge;
        for (int i = 0; i < growMsSteps.length; i++) {
            if (elapsed < growMsSteps[i]) {
                target = (i > 0) ? (int) Math.floor(cfg.maxAge * ((double) i / SMALL_STEPS.length)) : -1;
                break;
            }
        }
        if (target >= 0) {
            setStageIfChanged(l, b, target);
            if (target == cfg.maxAge) {
                grown.add(l);
                BlockStorage.addBlockInfo(b, KEY_GROWN, "1");
            }
        }
    }

    private void setStage(Block b, int age) {
        if (b.getType() != cfg.material) {
            // 种子头 → 作物方块转换：先记录种子头放置时的附着方向，转换后按其设置朝向
            // （如 COCOA 的 facing 须指向附着的原木），否则作物会因附着失效被原版反复破坏
            BlockFace support = seedSupportFace(b);
            b.setType(cfg.material);
            applyFacing(b, support);
            if (!b.getBlockData().isSupported(b)) {
                // 转换后在该位置无法存活（方向性作物四周无附着支撑、地面作物悬空等）：
                // 清数据并退还种子，避免「转换 → 原版破坏 → tick 复活」的无限循环
                discardUnsupportable(b);
                return;
            }
        }
        BlockState st = b.getState();
        if (st.getBlockData() instanceof Ageable a) {
            int target = Math.min(age, a.getMaximumAge());
            if (a.getAge() != target) {
                a.setAge(target);
                st.setBlockData(a);
                st.update(true);
            }
        }
    }

    /** 种子头（玩家头）的附着方向：墙式头朝外放置，附着面在其朝向的反方向；地面式头无附着信息，返回 null。 */
    private static BlockFace seedSupportFace(Block b) {
        if (b.getBlockData() instanceof Directional dir) {
            return dir.getFacing().getOppositeFace();
        }
        return null;
    }

    /**
     * 方向性作物（如 COCOA，facing 须指向附着的原木）转换/恢复后设置朝向：
     * 优先用种子头记录的附着方向，缺信息或指向非支撑方块时按 plantOn 水平扫描兜底。
     * 非方向性作物或无附着限制的作物直接跳过。
     */
    private void applyFacing(Block b, BlockFace support) {
        if (!directionalCrop || !(b.getBlockData() instanceof Directional dir)) return;
        List<Material> allowed = cfg.resolvedPlantOn();
        if (allowed == null || allowed.isEmpty()) return;
        BlockFace target = null;
        if (support != null && support.getModY() == 0
                && allowed.contains(b.getRelative(support).getType())) {
            target = support;
        } else {
            for (BlockFace f : HORIZONTAL_FACES) {
                if (allowed.contains(b.getRelative(f).getType())) { target = f; break; }
            }
        }
        if (target != null && dir.getFacing() != target) {
            dir.setFacing(target);
            b.setBlockData(dir);
        }
    }

    /** 作物在当前位置无法存活（悬空/无附着支撑）：清数据并退还 1 个种子，避免「复活 → 原版破坏」死循环。 */
    private void discardUnsupportable(Block b) {
        purge(b.getLocation());
        BlockStorage.clearBlockInfo(b);
        dropSeed(b, 1, 1.0, java.util.concurrent.ThreadLocalRandom.current());
        // 与 CropListener.breakCrop 一致：先上格后主格、关闭物理移除，
        // 防止另一半被原版邻块更新按原版战利品破坏（泄漏瓶子草等原版物品）
        Block up = b.getRelative(BlockFace.UP);
        if (up.getType() == b.getType()) {
            up.setType(Material.AIR, false);
        }
        b.setType(Material.AIR, false);
    }

    /** 作物是否成熟：原版 age 达到 min(配置 maxAge, 原版上限)。生长与骨粉催熟均由原版写 age，破坏时以此判定。 */
    private boolean isNaturallyMature(Block b) {
        if (b.getType() != cfg.material) return false;
        BlockState st = b.getState();
        if (st.getBlockData() instanceof Ageable a) {
            return a.getAge() >= Math.min(cfg.maxAge, a.getMaximumAge());
        }
        return false;
    }

    /** 阶段缓存写入：目标年龄与缓存一致时跳过方块写操作（spark 热点优化，行为不变）。 */
    private void setStageIfChanged(Location l, Block b, int age) {
        Integer cur = stage.get(l);
        if (cur != null && cur.intValue() == age) return;
        setStage(b, age);
        stage.put(l, age);
    }

    /** 破坏时调用：成熟（原版 age 满档，或紫颂定时成熟标记）则掉落作物/种子，否则掉 1 个种子。返回是否处理过。 */
    public boolean onBreak(Block b) {
        Location l = b.getLocation();
        boolean wasGrown = isNaturallyMature(b);
        if (isChorus()) {
            // 成熟标记兜底：紫颂基座可能被原版"移动式生长"重置过 age，以标记为准
            wasGrown = grown.remove(l) || wasGrown;
            lastUse.remove(l);
            stage.remove(l);
        }
        // 与项目其余随机逻辑一致使用 ThreadLocalRandom（免分配；破坏事件在主线程）
        java.util.Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (!wasGrown) {
            // 未成熟破坏：必掉 1 个对应种子（cropId 缺失时从掉落表回退找含 SEED 的项）
            dropSeed(b, 1, 1.0, rnd);
            return false;
        }
        List<CropDrop> drops = cfg.drops;
        if (cfg.weighted && !drops.isEmpty()) {
            // 对齐 FishingListener.select 的健壮加权选择：
            //   · total<=0（权重全非正的脏数据）时不产出，避免 rnd.nextDouble()*total 为负后逻辑错乱；
            //   · 兜底选末项，保证 total>0 时浮点边界/末项权重为 0 仍至少产出一个掉落（原实现循环走完会什么都不掉）。
            // R8：total 改用 CropCfg.weightTotal（load 期预算），消除每次收获对 drops 的求和（O(n)→O(1)，对齐 R4）。
            double total = cfg.weightTotal;
            if (total <= 0) return true;
            double r = rnd.nextDouble() * total;
            CropDrop picked = drops.get(drops.size() - 1);
            for (CropDrop d : drops) {
                r -= d.weight;
                if (r <= 0) { picked = d; break; }
            }
            dropItem(b, picked.id);
        } else {
            for (CropDrop d : drops) {
                if (rnd.nextDouble() < d.chance) dropItem(b, d.id);
            }
        }
        // 成熟破坏：原有掉落不变，额外按 seedDropChance 概率掉 1..seedDropMax 个种子
        dropSeed(b, 1 + rnd.nextInt(Math.max(1, cfg.seedDropMax)), cfg.seedDropChance, rnd);
        return true;
    }

    private void dropItem(Block b, String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        ItemStack stack;
        if (sf != null) stack = sf.getItem();
        else {
            Material m = Material.matchMaterial(id);
            if (m == null) {
                com.haiman233.worldtaste.WT.log("作物 " + getId() + " 的掉落物无法解析: " + id);
                return;
            }
            stack = new ItemStack(m);
        }
        b.getWorld().dropItemNaturally(b.getLocation(), stack.clone());
    }

    /** 掉落作物种子：cropId 优先，缺失时从掉落表回退找含 SEED 的项；chance&lt;1 时按概率判定。 */
    private void dropSeed(Block b, int count, double chance, java.util.Random rnd) {
        if (chance < 1.0 && rnd.nextDouble() >= chance) return;
        String seedId = cfg.cropId;
        if (seedId == null) {
            for (CropDrop d : cfg.drops) {
                if (d.id != null && d.id.contains("SEED")) { seedId = d.id; break; }
            }
        }
        if (seedId == null) return;
        SlimefunItem sf = SlimefunItem.getById(seedId);
        ItemStack stack = (sf != null) ? sf.getItem().clone() : null;
        if (stack == null) {
            Material m = Material.matchMaterial(seedId);
            if (m == null) return;
            stack = new ItemStack(m);
        }
        stack.setAmount(count);
        b.getWorld().dropItemNaturally(b.getLocation(), stack);
    }

    /** 清理一个位置的全部紫颂定时状态。 */
    private static void purge(Location l) {
        lastUse.remove(l);
        grown.remove(l);
        stage.remove(l);
    }

    private static boolean inChunk(Location l, World w, int cx, int cz) {
        return l.getWorld() == w && (l.getBlockX() >> 4) == cx && (l.getBlockZ() >> 4) == cz;
    }

    /**
     * 区块卸载兜底清理：在区块未加载期间被外部手段（WorldEdit、/fill、跨区块爆炸等）移除的
     * 紫颂作物不会再被 tick（也就不会走清理路径），其内存条目会在区块卸载时在此清除，
     * 避免按位置累积泄漏。普通作物零状态，无需清理。
     */
    public static final class ChorusStateCleanup implements Listener {

        /** 单例（状态为 CropBlock 的静态表，经静态方法清理）。 */
        public static final ChorusStateCleanup INSTANCE = new ChorusStateCleanup();

        private ChorusStateCleanup() {}

        @EventHandler
        public void onChunkUnload(ChunkUnloadEvent e) {
            Chunk c = e.getChunk();
            World w = c.getWorld();
            int cx = c.getX();
            int cz = c.getZ();
            lastUse.keySet().removeIf(l -> inChunk(l, w, cx, cz));
            grown.removeIf(l -> inChunk(l, w, cx, cz));
            stage.keySet().removeIf(l -> inChunk(l, w, cx, cz));
        }
    }
}
