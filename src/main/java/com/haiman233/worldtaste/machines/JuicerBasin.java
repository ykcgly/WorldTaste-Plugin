package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 榨汁盆（machines.yml 中 script: zhapen，材质为榨汁盆）。
 *
 * <p>投入：空盆时玩家往盆里扔物品（或手持物品右键盆投入）。盆内内容物为多集，容量上限 10
 * （不分类别，类似收纳袋）。每次投入后重新评估形态——内容物与某配方投入多集完全一致则锁定
 * 该配方（优先）；全部可混合（mix.recipes 列表配方的投入物）则进入混合榨汁；否则若为某配方
 * 的子集则继续等待补料；都不满足的物品拒绝投入（「这个物品不能榨汁！」），盆满或配方已锁定
 * 时再投入提示「这个榨汁盆中已经有物品了！」。</p>
 *
 * <p>榨汁：锁定配方或混合形态的盆需经一次榨汁产出——player 型站盆内跳跃每次 +1（混合固定
 * 玩家型），anvil 型铁砧砸盆顶每次 +4（铁砧碎裂）。进度动作栏显示「当前/总进度」。满进度
 * 播放经验音效、提示「已经榨好了！」，掉落「果渣」（数量=投入材料总数），盆转为榨汁盆含水
 * （水量=剩余接取份数）。未锁定的等待补料形态踩踏无效。</p>
 *
 * <p>接取（统一剂量制，无 per-配方容器）：玻璃瓶每次接 1 份、水量 -1（配方产物含榨汁师
 * 名单；混合产物 lore 标注内容物清单）；铁桶仅满盆（水量=满额份数）可一次接完并清空，
 * 未满提示「这个盆不是满的」。接完还原空盆。空手蹲下右键盆可清空全部内容物（丢弃不返还）。
 * 桶装混合产物禁止倒出。</p>
 *
 * <p>状态（内容物多集/进度/接取份数/榨汁师）经 {@link BlockStorage} 持久化，形态在载入时
 * 重新评估；产物内容物写入 PDC（juice_contents/juice_bucket）。展示实体不落盘，缺失时由
 * tick 补生成。盆防炸。</p>
 */
public class JuicerBasin extends SlimefunItem {

    /** 物品 id（指南入口注入与展示菜单定位用）。 */
    public static final String ITEM_ID = "WT_ZHAPEN";

    private static final String KEY_RECIPE = "wt-juicer-recipe";
    private static final String KEY_PROGRESS = "wt-juicer-progress";
    private static final String KEY_PLAYERS = "wt-juicer-players";
    private static final String KEY_DOSES = "wt-juicer-doses";
    private static final String KEY_TIME = "wt-juicer-time";
    private static final String KEY_SUGAR = "wt-juicer-sugar";
    private static final String KEY_MIX = "wt-juicer-mix";
    /** 掉落物一次性提示标记（防止同一物品反复刷消息）。 */
    private static final String TAG_WARNED = "wt-juicer-warned";
    /** 盆容量上限（不分类别的物品总数，类似收纳袋）。 */
    private static final int MAX_CAPACITY = 10;
    /** 榨汁盆最大含水等级。 */
    private static final int MAX_WATER = 3;
    /** 果渣物品 id（榨汁完成按投入总数掉落）。 */
    private static final String POMACE_ID = "WT_GUOZHA";

    /** 投入评估结果。 */
    private static final int INSERT_OK = 0;
    private static final int INSERT_BUSY = 1;
    private static final int INSERT_INVALID = 2;

    /** 运行期状态缓存（位置 → 状态；空盆无条目，事件与 tick 均主线程访问）。 */
    private static final Map<Location, BasinState> STATES = new HashMap<>();
    /** 位置索引（世界 → (打包坐标 → 状态)）：onMove 热路径免分配查询，与 STATES 同步维护。 */
    private static final Map<World, Map<Long, BasinState>> STATE_INDEX = new HashMap<>();

    /** 方块坐标打包为 long（x/z 26 位 + y 12 位，覆盖世界边界与全高度）。 */
    private static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static void indexState(World w, int x, int y, int z, BasinState st) {
        STATE_INDEX.computeIfAbsent(w, k -> new HashMap<>()).put(posKey(x, y, z), st);
    }

    private static void unindexState(World w, int x, int y, int z) {
        Map<Long, BasinState> m = STATE_INDEX.get(w);
        if (m != null) m.remove(posKey(x, y, z));
    }

    /** 热路径零分配查询。 */
    private static BasinState lookup(World w, int x, int y, int z) {
        Map<Long, BasinState> m = STATE_INDEX.get(w);
        return m == null ? null : m.get(posKey(x, y, z));
    }
    /** 已知空盆位置（无内容物负缓存：避免空盆每 tick 读 BlockStorage）。 */
    private static final Set<Location> EMPTY_KNOWN = new HashSet<>();
    /** 投入扫描活跃标记（位置 → 扫描截止时间；附近有玩家投掷时激活快速扫描）。 */
    private static final Map<Location, Long> SCAN_UNTIL = new HashMap<>();
    /** 已输出过首次 tick 日志的位置（按世界分组打包坐标，避免每 tick 哈希 Location）。 */
    public JuicerBasin(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe) {
        super(group, item, rt, recipe);
        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }

            @Override
            public void tick(Block b, SlimefunItem sf, Config data) {
                JuicerBasin.this.tick(b);
            }
        });
        addItemHandler(new BlockBreakHandler(true, true) {
            @Override
            public void onPlayerBreak(BlockBreakEvent e, ItemStack tool, List<ItemStack> drops) {
                BasinState st = STATES.remove(e.getBlock().getLocation());
                unindexState(e.getBlock().getWorld(), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
                if (st != null) removeDisplays(st);
            }

            @Override
            public boolean isExplosionAllowed(Block b) {
                return false;
            }
        });
    }

    private void tick(Block b) {
        Material type = b.getType();
        if (type != Material.CAULDRON && type != Material.WATER_CAULDRON) {
            // 方块被非玩家手段替换（无 BlockBreakHandler 路径）：按破坏清理，防幽灵状态
            BasinState st = STATES.remove(b.getLocation());
            unindexState(b.getWorld(), b.getX(), b.getY(), b.getZ());
            if (st != null) removeDisplays(st);
            if (BlockStorage.hasBlockInfo(b)) BlockStorage.clearBlockInfo(b);
            return;
        }
        BasinState st = state(b);
        if (st != null) {
            // 展示实体不落盘：缺失/数量不符（重启/区块卸载）时按内容物重建
            int expected = st.doses > 0 ? 0 : st.total;
            boolean broken = false;
            for (ItemDisplay d : st.displays) {
                if (!d.isValid()) {
                    broken = true;
                    break;
                }
            }
            if (st.displays.size() != expected || broken) refreshDisplays(b, st);
        }
        // 掉落物扫描：附近有玩家投掷时每 2 tick 快扫；否则每 50 tick 慢扫兜底（远距离抛入场景）
        Long until = SCAN_UNTIL.get(b.getLocation());
        boolean active = until != null && until >= System.currentTimeMillis();
        if (until != null && !active) SCAN_UNTIL.remove(b.getLocation());
        if (!active && b.getWorld().getGameTime() % 50 != 0) return;
        Collection<Entity> dropped = b.getWorld().getNearbyEntities(
                b.getLocation().add(0.5, 0.45, 0.5), 0.45, 0.45, 0.45, en -> en instanceof org.bukkit.entity.Item);
        for (Entity en : dropped) {
            org.bukkit.entity.Item item = (org.bukkit.entity.Item) en;
            int verdict = canInsert(st, JuicerRecipe.refOf(item.getItemStack()));
            if (verdict == INSERT_BUSY) {
                reject(item, "§c这个榨汁盆中已经有物品了！");
                continue;
            }
            if (verdict == INSERT_INVALID) {
                reject(item, "§c这个物品不能榨汁！");
                continue;
            }
            if (consumeDropped(item)) doInsert(b, st, item.getItemStack());
            st = STATES.get(b.getLocation());
        }
    }

    /**
     * 投入评估：已开始榨汁（锁定）/已榨好待接取/容量满 → BUSY；加入后既非配方比例匹配、
     * 非全可混合、也非某配方子集 → INVALID；否则 OK（投入阶段不锁定，开始榨汁时才锁定）。
     */
    private static int canInsert(BasinState st, String ref) {
        if (st != null) {
            if (st.started || st.doses > 0 || st.total >= MAX_CAPACITY) return INSERT_BUSY;
        }
        Map<String, Integer> temp = st == null ? new LinkedHashMap<>() : new LinkedHashMap<>(st.contents);
        temp.merge(ref, 1, Integer::sum);
        return JuicerRecipe.validContents(temp, st == null ? 1 : st.total + 1)
                ? INSERT_OK : INSERT_INVALID;
    }

    /** 掉落物消耗 1 个（全部消耗则移除实体）。 */
    private static boolean consumeDropped(org.bukkit.entity.Item item) {
        int amt = item.getItemStack().getAmount();
        if (amt <= 1) {
            item.remove();
        } else {
            item.getItemStack().setAmount(amt - 1);
        }
        return true;
    }

    /** 掉落物一次性提示：优先提示投掷者；来源未知（漏斗/容器弹出等）时提示附近玩家。 */
    private static void reject(org.bukkit.entity.Item item, String msg) {
        if (item.getScoreboardTags().contains(TAG_WARNED)) return;
        item.addScoreboardTag(TAG_WARNED);
        Player thrower = item.getThrower() != null ? Bukkit.getPlayer(item.getThrower()) : null;
        if (thrower != null) {
            thrower.sendMessage(msg);
            return;
        }
        for (Player near : item.getWorld().getNearbyEntitiesByType(Player.class, item.getLocation(), 4)) {
            near.sendMessage(msg);
        }
    }

    /** 执行投入：内容物 +1、持久化并重建展示实体。调用前须 canInsert == OK（投入不锁定配方）。 */
    private static void doInsert(Block b, BasinState st, ItemStack stack) {
        String ref = JuicerRecipe.refOf(stack);
        if (st == null) {
            st = new BasinState();
            STATES.put(b.getLocation(), st);
            indexState(b.getWorld(), b.getX(), b.getY(), b.getZ(), st);
        }
        EMPTY_KNOWN.remove(b.getLocation());
        st.contents.merge(ref, 1, Integer::sum);
        st.total++;
        BlockStorage.addBlockInfo(b, KEY_MIX, JuicerRecipe.joinContents(st.contents));
        refreshDisplays(b, st);
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
    }

    /**
     * 开始榨汁（首次踩踏/铁砧砸落时锁定形态）：内容物为某配方投入多集的整数倍 → 锁定该配方
     * （接取份数 = 倍率 × yield）；全部可混合 → 混合榨汁（水量恒 3 份）；否则（等待补料）
     * 返回 false 不开始。
     */
    private static boolean tryStartJuice(BasinState st) {
        if (st.started) return true;
        JuicerRecipe.MatchResult m = JuicerRecipe.matchProportional(st.contents, st.total);
        if (m != null) {
            st.recipe = m.recipe;
            st.multiplier = m.multiplier;
        } else if (JuicerRecipe.allMixable(st.contents)) {
            st.mixFlag = true;
        } else {
            return false;
        }
        st.started = true;
        return true;
    }

    /** 惰性读取状态：内存缓存缺失时从 BlockStorage 重建（重启/首次访问）。空盆返回 null。 */
    private static BasinState state(Block b) {
        Location l = b.getLocation();
        BasinState st = STATES.get(l);
        if (st != null) return st;
        if (EMPTY_KNOWN.contains(l)) return null; // 已知空盆：跳过存储读取
        String mixData = BlockStorage.getLocationInfo(l, KEY_MIX);
        if (mixData == null || mixData.isEmpty()) {
            EMPTY_KNOWN.add(l);
            return null;
        }
        st = new BasinState();
        st.contents.putAll(JuicerRecipe.parseContents(mixData));
        st.total = 0;
        for (int n : st.contents.values()) st.total += n;
        if (st.total == 0) {
            clearStateKeys(b);
            return null;
        }
        st.progress = parseInt(BlockStorage.getLocationInfo(l, KEY_PROGRESS));
        String names = BlockStorage.getLocationInfo(l, KEY_PLAYERS);
        if (names != null && !names.isEmpty()) {
            for (String n : names.split(",")) {
                if (!n.isEmpty()) st.players.add(n);
            }
        }
        st.doses = parseInt(BlockStorage.getLocationInfo(l, KEY_DOSES));
        st.completedAt = parseLong(BlockStorage.getLocationInfo(l, KEY_TIME));
        st.totalSugar = parseInt(BlockStorage.getLocationInfo(l, KEY_SUGAR));
        // 进行中/已榨好的批次恢复锁定形态（纯累积批次保持未锁定，开始榨汁时再锁）
        if (st.progress > 0 || st.doses > 0) tryStartJuice(st);
        st.dosesMax = st.recipe != null ? st.recipe.yield * st.multiplier : MAX_WATER;
        STATES.put(l, st);
        indexState(b.getWorld(), b.getX(), b.getY(), b.getZ(), st);
        return st;
    }

    private static void clearStateKeys(Block b) {
        EMPTY_KNOWN.add(b.getLocation());
        BlockStorage.addBlockInfo(b, KEY_RECIPE, null);
        BlockStorage.addBlockInfo(b, KEY_PROGRESS, null);
        BlockStorage.addBlockInfo(b, KEY_PLAYERS, null);
        BlockStorage.addBlockInfo(b, KEY_DOSES, null);
        BlockStorage.addBlockInfo(b, KEY_MIX, null);
        BlockStorage.addBlockInfo(b, KEY_TIME, null);
        BlockStorage.addBlockInfo(b, KEY_SUGAR, null);
    }

    /** 还原空盆：清状态/展示实体/持久化数据并复原为无水榨汁盆。 */
    private static void resetBasin(Block b, BasinState st) {
        removeDisplays(st);
        unindexState(b.getWorld(), b.getX(), b.getY(), b.getZ());
        STATES.remove(b.getLocation());
        clearStateKeys(b);
        b.setType(Material.CAULDRON);
    }

    /** 盆水量同步：0 = 无水空锅，>0 = 装水榨汁盆（上限 3）。 */
    private static void syncLevel(Block b, int level) {
        if (level <= 0) {
            b.setType(Material.CAULDRON);
            return;
        }
        b.setType(Material.WATER_CAULDRON);
        if (b.getBlockData() instanceof Levelled lv) {
            lv.setLevel(Math.min(MAX_WATER, level));
            b.setBlockData(lv);
        }
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String joinPlayers(BasinState st) {
        return String.join(",", st.players);
    }

    /** 当前榨汁所需总进度：锁定配方用其 progress，混合用 mix.progress。 */
    private static int progressTotal(BasinState st) {
        return st.recipe != null ? st.recipe.progress
                : (JuicerRecipe.mix != null ? JuicerRecipe.mix.progress : 1);
    }

    /** 榨汁进度推进：水花迸溅 + 写进度，满时完成。 */
    private static void advance(Block b, BasinState st, int add, Player feedback) {
        // 每次 +1（踩踏）/+4（铁砧）：盆沿水花迸溅一次
        b.getWorld().spawnParticle(Particle.SPLASH,
                b.getLocation().add(0.5, 0.9, 0.5), 12, 0.4, 0.02, 0.4, 0);
        b.getWorld().spawnParticle(Particle.FALLING_WATER,
                b.getLocation().add(0.5, 0.8, 0.5), 8, 0.45, 0.05, 0.45, 0);
        int total = progressTotal(st);
        st.progress = Math.min(st.progress + add, total);
        BlockStorage.addBlockInfo(b, KEY_PROGRESS, String.valueOf(st.progress));
        if (st.progress >= total) {
            complete(b, st, feedback);
        } else if (feedback != null) {
            feedback.sendActionBar(Component.text(st.progress, NamedTextColor.YELLOW)
                    .append(Component.text("/", NamedTextColor.GRAY))
                    .append(Component.text(total, NamedTextColor.AQUA)));
        }
    }

    /** 榨好：写入接取份数（配方按倍率放大、混合恒 3 份）、掉落果渣（投入总数）、水量同步并提示。 */
    private static void complete(Block b, BasinState st, Player feedback) {
        st.doses = st.recipe != null ? st.recipe.yield * st.multiplier : MAX_WATER;
        st.dosesMax = st.doses;
        st.completedAt = System.currentTimeMillis();
        st.totalSugar = JuicerRecipe.totalSugar(st.contents);
        BlockStorage.addBlockInfo(b, KEY_DOSES, String.valueOf(st.doses));
        BlockStorage.addBlockInfo(b, KEY_TIME, String.valueOf(st.completedAt));
        BlockStorage.addBlockInfo(b, KEY_SUGAR, String.valueOf(st.totalSugar));
        syncLevel(b, st.doses);
        // 原料已成汁：收起内容物展示，以水位表现
        removeDisplays(st);
        // 果渣：按投入材料总数量掉落
        SlimefunItem pomace = io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(POMACE_ID);
        if (pomace != null && st.total > 0) {
            ItemStack out = pomace.getItem().clone();
            out.setAmount(Math.min(st.total, out.getMaxStackSize()));
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.6, 0.5), out);
        }
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        if (feedback != null) feedback.sendActionBar(Component.text("已经榨好了！", NamedTextColor.GREEN));
    }

    /** 盆附近最近的玩家（完成/铁砧进度反馈用），盆周边 2 格内无人返回 null。 */
    private static Player nearbyPlayer(Block b) {
        Collection<Player> ps = b.getWorld().getNearbyEntitiesByType(Player.class, b.getLocation().add(0.5, 0.5, 0.5), 2);
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : ps) {
            double d = p.getLocation().distanceSquared(b.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** 发放物品，背包满则掉落在玩家脚下。 */
    private static void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
    }

    /** 蹲下空手左键预览：内容物清单（本地化名称×数量）+ 容量 + 当前状态。 */
    private static Component preview(BasinState st) {
        Component prefix = Component.text("[榨汁盆] ", NamedTextColor.GOLD);
        if (st == null || st.contents.isEmpty()) {
            return prefix.append(Component.text("盆内空空如也", NamedTextColor.GRAY));
        }
        net.kyori.adventure.text.TextComponent.Builder msg = net.kyori.adventure.text.Component.text()
                .append(prefix)
                .append(Component.text("内容物: ", NamedTextColor.GRAY));
        boolean first = true;
        for (Map.Entry<String, Integer> en : st.contents.entrySet()) {
            if (!first) msg.append(Component.text("、", NamedTextColor.GRAY));
            first = false;
            msg.append(JuicerRecipe.nameComponent(en.getKey()));
            if (en.getValue() > 1) msg.append(Component.text("×" + en.getValue(), NamedTextColor.GRAY));
        }
        msg.append(Component.text("（" + st.total + "/" + MAX_CAPACITY + "）", NamedTextColor.DARK_GRAY));
        int sugar = JuicerRecipe.totalSugar(st.contents);
        if (sugar > 0) msg.append(Component.text(" 糖分:" + sugar, NamedTextColor.LIGHT_PURPLE));
        String status;
        if (st.doses > 0) {
            status = "已榨好，剩余 " + st.doses + " 份";
        } else if (st.started) {
            status = (st.isMix() ? "混合榨汁中 " : "榨汁中 ") + st.progress + "/" + progressTotal(st);
        } else {
            status = "等待投料";
        }
        return msg.append(Component.text(" " + status, NamedTextColor.YELLOW)).build();
    }

    /** 原版物品判定（排除同材质的粘液物品）。 */
    private static boolean isVanilla(ItemStack item, Material m) {
        return item != null && item.getType() == m
                && io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item) == null;
    }

    private static ItemDisplay spawnItemDisplay(World world, Location loc, ItemStack item, float scale, float yaw) {
        return world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setPersistent(false);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotationY(yaw),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    /**
     * 按内容物重建展示实体：每单位物品一个，环形散布在盆内并随机朝向/高低差，模拟自然堆放；
     * 单件时居中稍大。已榨好批次（doses > 0）原料已成汁，不再展示，以水位表现。
     */
    private static void refreshDisplays(Block b, BasinState st) {
        removeDisplays(st);
        if (st.doses > 0 || st.total <= 0) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = Math.min(st.total, MAX_CAPACITY);
        float scale = n == 1 ? 0.7f : 0.6f;
        double baseAngle = rnd.nextDouble() * Math.PI * 2;
        int i = 0;
        for (Map.Entry<String, Integer> en : st.contents.entrySet()) {
            ItemStack item = JuicerRecipe.refToItem(en.getKey());
            for (int k = 0; k < en.getValue() && i < n; k++, i++) {
                double angle = baseAngle + Math.PI * 2 * i / n;
                double radius = n == 1 ? 0 : 0.18;
                Location loc = new Location(b.getWorld(),
                        b.getX() + 0.5 + Math.cos(angle) * radius,
                        b.getY() + 0.26 + rnd.nextDouble() * 0.08,
                        b.getZ() + 0.5 + Math.sin(angle) * radius);
                st.displays.add(spawnItemDisplay(b.getWorld(), loc, item, scale,
                        (float) (rnd.nextDouble() * Math.PI * 2)));
            }
        }
    }

    private static void removeDisplays(BasinState st) {
        for (ItemDisplay d : st.displays) {
            d.remove();
        }
        st.displays.clear();
    }

    /** 盆状态：内容物多集 + 形态（锁定配方 / 混合 / 等待补料）+ 榨汁与接取进度。 */
    private static final class BasinState {
        /** 内容物多集：ref(mc:XXX/sf:XXX) → 数量（按投入顺序）。 */
        final Map<String, Integer> contents = new LinkedHashMap<>();
        int total;
        /** 是否已开始榨汁（开始后锁定形态，拒绝再投入）。 */
        boolean started;
        /** 锁定的配方（开始榨汁时按比例匹配锁定），null = 混合/等待补料。 */
        JuicerRecipe recipe;
        /** 锁定配方的倍率（内容物 = 倍率 × 配方投入多集），接取份数 = 倍率 × yield。 */
        int multiplier = 1;
        /** 混合形态（开始榨汁时未匹配配方但全部可混合；水量恒为 3 份）。 */
        boolean mixFlag;
        int progress;
        /** 剩余接取份数（榨好后 >0）。 */
        int doses;
        /** 本次批次的满额份数（桶满判定）。 */
        int dosesMax;
        /** 榨好时间（epoch millis，产物 lore 的 %time% 占位符用）。 */
        long completedAt;
        /** 本批次总糖分（Σ 材料糖分 × 数量，榨好时定格）。 */
        int totalSugar;
        final LinkedHashSet<String> players = new LinkedHashSet<>();
        /** 盆内物品展示实体（每单位一个；不落盘，缺失时由 tick 重建）。 */
        final List<ItemDisplay> displays = new ArrayList<>();

        boolean isMix() {
            return started && recipe == null && mixFlag;
        }

        boolean ready() {
            return doses > 0;
        }
    }

    /** 榨汁盆事件监听：跳跃踩踏进度 / 铁砧砸落进度 / 投入与接取 / 清空 / 桶装产物禁倒出。 */
    public static final class Listener implements org.bukkit.event.Listener {

        public static final Listener INSTANCE = new Listener();

        /** 已武装玩家（站在可榨汁的盆内），跳起越出盆格时记一次踩踏。 */
        private final Set<UUID> armed = new HashSet<>();
        /** 内容物预览冷却（玩家 → 上次提示时间戳，0.5 秒防刷屏）。 */
        private final Map<UUID, Long> previewCooldown = new HashMap<>();
        /** 材料不足提示冷却（玩家 → 上次提示时间戳，1 秒防动作栏刷屏）。 */
        private final Map<UUID, Long> hintCooldown = new HashMap<>();

        private Listener() {}

        /**
         * 玩家型榨汁踩踏检测（Paper 1.21.1+ 已移除 PlayerJumpEvent，改用移动事件）：
         * 脚部方块在盆内且未开始榨汁 → 先锁定形态（比例匹配配方 / 混合；等待补料则动作栏
         * 提示并跳过，带 1 秒节流）；可踩踏盆 → 武装；已武装玩家脚部越到盆上方一格
         * （一次跳跃恰好穿越一次）→ +1 并解除武装，落回盆内重新武装。已榨好盆不响应踩踏。
         */
        @EventHandler(ignoreCancelled = true)
        public void onMove(PlayerMoveEvent e) {
            if (STATES.isEmpty()) return;
            Location feet = e.getTo();
            if (feet == null) return;
            Location from = e.getFrom();
            // 视角转动不处理（坐标未变）
            if (from.getX() == feet.getX() && from.getY() == feet.getY() && from.getZ() == feet.getZ()) return;
            Player p = e.getPlayer();
            World w = feet.getWorld();
            int bx = feet.getBlockX(), by = feet.getBlockY(), bz = feet.getBlockZ();
            BasinState st = lookup(w, bx, by, bz);
            if (st != null && st.doses <= 0
                    && BlockStorage.check(w.getBlockAt(bx, by, bz)) instanceof JuicerBasin) {
                if (!st.started) {
                    if (!tryStartJuice(st)) {
                        long now = System.currentTimeMillis();
                        Long last = hintCooldown.get(p.getUniqueId());
                        if (last == null || now - last >= 1000) {
                            hintCooldown.put(p.getUniqueId(), now);
                            p.sendActionBar(Component.text("材料不足，无法开始榨汁", NamedTextColor.RED));
                        }
                    }
                    return;
                }
                // 混合榨汁与玩家型配方响应跳跃；铁砧型配方不响应
                if (st.isMix() || st.recipe == null || !st.recipe.anvil) {
                    armed.add(p.getUniqueId());
                }
                return;
            }
            if (armed.remove(p.getUniqueId())) {
                BasinState target = lookup(w, bx, by - 1, bz);
                if (target != null && target.doses <= 0 && target.started) {
                    Block below = w.getBlockAt(bx, by - 1, bz);
                    if (BlockStorage.check(below) instanceof JuicerBasin
                            && (target.isMix() || target.recipe == null || !target.recipe.anvil)) {
                        target.players.add(p.getName());
                        BlockStorage.addBlockInfo(below, KEY_PLAYERS, joinPlayers(target));
                        below.getWorld().playSound(below.getLocation().add(0.5, 0.5, 0.5),
                                Sound.ENTITY_SLIME_JUMP, 1f, 1f);
                        advance(below, target, 1, p);
                    }
                }
            }
        }

        /**
         * 铁砧配方：铁砧落到盆顶每次 +4（未开始榨汁的盆同时锁定形态；混合榨汁不响应铁砧）。
         * 铁砧损耗按 anvil-damage 配置：关闭则永不损坏（留在盆顶）；开启则每次砸落按
         * anvil-damage-chance/10 概率降低一档耐久（ANVIL→CHIPPED→DAMAGED→碎裂）。
         */
        @EventHandler(ignoreCancelled = true)
        public void onAnvilLand(EntityChangeBlockEvent e) {
            if (!(e.getEntity() instanceof FallingBlock fb)) return;
            Material m = fb.getBlockData().getMaterial();
            if (m != Material.ANVIL && m != Material.CHIPPED_ANVIL && m != Material.DAMAGED_ANVIL) return;
            Block basin = e.getBlock().getRelative(BlockFace.DOWN);
            BasinState st = STATES.get(basin.getLocation());
            if (st == null || st.doses > 0) return;
            if (!(BlockStorage.check(basin) instanceof JuicerBasin)) return;
            if (!st.started && !tryStartJuice(st)) return;
            if (st.recipe == null || !st.recipe.anvil) return;
            // 落点方块在事件回调内改动不安全：下一 tick 处理铁砧损耗
            Block landed = e.getBlock();
            Bukkit.getScheduler().runTask(WT.plugin, () -> {
                if (landed.getType() != m) return;
                if (!JuicerRecipe.anvilDamage) return;
                if (ThreadLocalRandom.current().nextInt(10)
                        >= JuicerRecipe.anvilDamageChance) return;
                BlockFace face = landed.getBlockData() instanceof org.bukkit.block.data.Directional d
                        ? d.getFacing() : BlockFace.NORTH;
                Material next;
                switch (m) {
                    case ANVIL -> next = Material.CHIPPED_ANVIL;
                    case CHIPPED_ANVIL -> next = Material.DAMAGED_ANVIL;
                    default -> next = Material.AIR;
                }
                if (next == Material.AIR) {
                    landed.setType(Material.AIR);
                    landed.getWorld().playSound(landed.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1f, 1f);
                } else {
                    Material nm = next;
                    landed.setBlockData(nm.createBlockData(d ->
                            ((org.bukkit.block.data.Directional) d).setFacing(face)));
                    landed.getWorld().playSound(landed.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 0.5f, 1.2f);
                }
            });
            advance(basin, st, 4, nearbyPlayer(basin));
        }

        /**
         * 蹲下 + 空手左键盆：聊天框提示内容物与状态（0.5 秒冷却防刷屏），同时取消挖掘，
         * 防止按住左键误破坏盆体（拆除请持物或不蹲下）。
         */
        @EventHandler
        public void onPreview(PlayerInteractEvent e) {
            if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
            if (e.getHand() != EquipmentSlot.HAND || !e.getPlayer().isSneaking()) return;
            ItemStack hand = e.getItem();
            if (hand != null && !hand.getType().isAir()) return;
            Block b = e.getClickedBlock();
            if (b == null) return;
            Material t = b.getType();
            if (t != Material.CAULDRON && t != Material.WATER_CAULDRON) return;
            BasinState st = STATES.get(b.getLocation());
            if (st == null && !(BlockStorage.check(b) instanceof JuicerBasin)) return;
            e.setCancelled(true);
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            Long last = previewCooldown.get(p.getUniqueId());
            if (last != null && now - last < 500) return;
            previewCooldown.put(p.getUniqueId(), now);
            p.sendMessage(preview(st));
        }

        /** 桶装产物禁止倒出（任何右键使用一律取消；水桶材质预过滤减少 meta 读取）。 */
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onBucketPour(PlayerInteractEvent e) {
            ItemStack item = e.getItem();
            if (item == null || item.getType() != Material.WATER_BUCKET || !item.hasItemMeta()) return;
            if (!item.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE)) return;
            e.setCancelled(true);
        }

        /** 投入与接取：空盆可投入；有内容盆可续投/榨汁后按剂量接取；空手蹲下右键清空。 */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            Block b = e.getClickedBlock();
            if (b == null) return;
            Material t = b.getType();
            if (t != Material.CAULDRON && t != Material.WATER_CAULDRON) return;
            BasinState st = STATES.get(b.getLocation());
            if (st == null) {
                // 空盆：主手持物右键投入（等同扔入），无匹配则提示
                if (e.getHand() != EquipmentSlot.HAND) return;
                if (!(BlockStorage.check(b) instanceof JuicerBasin)) return;
                ItemStack hand = e.getItem();
                if (hand == null || hand.getType().isAir()) return;
                insertFromHand(e, b, hand);
                return;
            }
            if (!(BlockStorage.check(b) instanceof JuicerBasin)) return;
            e.setCancelled(true);
            Player p = e.getPlayer();
            // 空手蹲下右键：清空盆内内容物（丢弃不返还），盆还原为空
            if (p.isSneaking() && e.getHand() == EquipmentSlot.HAND
                    && (e.getItem() == null || e.getItem().getType().isAir())) {
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_GENERIC_SPLASH, 1f, 1f);
                resetBasin(b, st);
                return;
            }
            // 已榨好：剂量制接取
            if (st.doses <= 0) {
                // 未榨好：可继续投入（锁定配方/满盆拒绝）
                if (e.getHand() == EquipmentSlot.HAND) {
                    ItemStack hand = e.getItem();
                    if (hand != null && !hand.getType().isAir()) insertFromHand(e, b, hand);
                }
                return;
            }
            ItemStack hand = e.getItem();
            // 瓶子：接取 1 份，水量 -1
            if (isVanilla(hand, Material.GLASS_BOTTLE)) {
                int perDose = st.dosesMax > 0 ? st.totalSugar / st.dosesMax : 0;
                give(p, st.recipe != null
                        ? st.recipe.buildResult(st.players, st.completedAt, perDose)
                        : JuicerRecipe.buildMixProduct(false, st.contents, st.players, st.completedAt, perDose));
                consumeOne(p, e.getHand());
                st.doses--;
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
                if (st.doses <= 0) {
                    resetBasin(b, st);
                } else {
                    BlockStorage.addBlockInfo(b, KEY_DOSES, String.valueOf(st.doses));
                    syncLevel(b, st.doses);
                }
                return;
            }
            // 桶：仅满盆（水量=满额份数）一次接取全部并清空
            if (isVanilla(hand, Material.BUCKET)) {
                if (st.doses < st.dosesMax) {
                    p.sendMessage("§c这个盆不是满的");
                    return;
                }
                if (st.recipe != null) {
                    // 桶装形态产物（lore 与瓶装一致，禁止倒出；糖分为整批总量）
                    give(p, st.recipe.buildBucketResult(st.players, st.completedAt, st.totalSugar));
                } else {
                    give(p, JuicerRecipe.buildMixProduct(true, st.contents, st.players, st.completedAt, st.totalSugar));
                }
                consumeOne(p, e.getHand());
                b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_FILL, 1f, 1f);
                resetBasin(b, st);
            }
        }

        /** 主手持物右键投入：评估通过则消耗 1 个并加入盆中，否则按原因提示。 */
        private static void insertFromHand(PlayerInteractEvent e, Block b, ItemStack hand) {
            int verdict = canInsert(STATES.get(b.getLocation()), JuicerRecipe.refOf(hand));
            if (verdict == INSERT_BUSY) {
                e.getPlayer().sendMessage("§c这个榨汁盆中已经有物品了！");
                return;
            }
            if (verdict == INSERT_INVALID) {
                e.getPlayer().sendMessage("§c这个物品不能榨汁！");
                return;
            }
            consumeOne(e.getPlayer(), EquipmentSlot.HAND);
            doInsert(b, STATES.get(b.getLocation()), hand);
        }

        /**
         * 玩家投掷物品：激活 8 格内榨汁盆的快速掉落物扫描（10 秒窗口）。
         * 空置盆平时不做实体查询，仅在有投掷行为时短暂高频扫描。
         */
        @EventHandler
        public void onDrop(PlayerDropItemEvent e) {
            Location l = e.getItemDrop().getLocation();
            long until = System.currentTimeMillis() + 10_000;
            for (Location bl : STATES.keySet()) {
                if (bl.getWorld() == l.getWorld() && bl.distanceSquared(l) <= 64) {
                    SCAN_UNTIL.put(bl, until);
                }
            }
            for (Location bl : EMPTY_KNOWN) {
                if (bl.getWorld() == l.getWorld() && bl.distanceSquared(l) <= 64) {
                    SCAN_UNTIL.put(bl, until);
                }
            }
        }

        /** 从指定手消耗 1 个物品（到 0 清空槽位，防幽灵物品）。 */
        private static void consumeOne(Player p, EquipmentSlot slot) {
            PlayerInventory inv = p.getInventory();
            ItemStack held = inv.getItem(slot);
            if (held == null) return;
            if (held.getAmount() <= 1) inv.setItem(slot, null);
            else held.setAmount(held.getAmount() - 1);
        }
    }
}
