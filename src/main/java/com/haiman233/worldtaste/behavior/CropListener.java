package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.items.CropBlock;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * 作物方块破坏处理：
 * <ol>
 *   <li>破坏作物主格：禁用原版掉落，成熟则掉成品/种子（未成熟掉 1 种子）；2 格高作物（如瓶子草）
 *       同时清除上方残留格；</li>
 *   <li>破坏 2 格高作物的上格：联动破坏主格（掉成品/种子）并清数据；</li>
 *   <li>破坏种子附着的支撑方块（耕地/末地石等）：联动破坏上方作物；</li>
 *   <li>非玩家事件（爆炸、水冲、活塞推拉、火烧、耕地踩踏、实体顶替）：作物被波及/失去支撑时
 *       同样按破坏处理（掉成品/种子）并删除粘液数据——否则残留数据会让 tick 把方块复原，
 *       出现「水冲不走、拉不倒」的假活死循环；</li>
 *   <li>生长限高：配置 maxAge 低于原版上限的作物（瓜茎等）长到成熟年龄后取消原版生长事件
 *       （BlockGrowEvent），停留在成熟阶段，不进入原版满龄行为（茎结果实）。</li>
 * </ol>
 */
public final class CropListener implements Listener {

    public static final CropListener INSTANCE = new CropListener();

    private CropListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            // 禁用原版掉落：作物方块已被 tick 转成 WHEAT 等原版材质，否则会额外掉原版作物/种子
            e.setDropItems(false);
            breakCrop(b, crop);
            return;
        }
        // 自身即其他粘液方块（机器/装饰等）：作物只种在原版方块上（plantOn 限定原版材质），
        // 且 2 格高作物的上格不含粘液数据，故它既不可能是作物的上格残留也不可能是支撑方块，
        // 跳过下方/上方两次数据查询（粘液方块破坏高频路径）
        if (sf != null) return;
        // 2 格高作物：破坏上格 → 联动破坏主格
        Block below = b.getRelative(BlockFace.DOWN);
        SlimefunItem belowSf = BlockStorage.check(below);
        if (belowSf instanceof CropBlock cropBelow) {
            e.setDropItems(false);
            breakCrop(below, cropBelow);
            return;
        }
        // 支撑方块被破坏 → 联动破坏上方作物（保留支撑方块自身的原版掉落）
        Block above = b.getRelative(BlockFace.UP);
        SlimefunItem aboveSf = BlockStorage.check(above);
        if (aboveSf instanceof CropBlock cropAbove) {
            breakCrop(above, cropAbove);
        }
    }

    /** 爆炸波及：被炸方块的上/下若有作物（支撑被炸 / 2 格高上格被炸），联动破坏并清数据。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) {
            cleanupAdjacent(b);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            cleanupAdjacent(b);
        }
    }

    /**
     * 流体冲刷（水流冲走作物、岩浆烧毁等）：被流体顶替的作物按非玩家破坏处理（掉成品/种子 + 清数据）。
     * 在事件阶段（流体尚未流入）提前破坏并清数据，原版的流体破坏掉落便不会再发生，避免双重掉落；
     * 若只清方块不清数据，残留数据会让 tick 的恢复守卫把作物复原，出现「水冲不走」的死循环。
     */
    @EventHandler(ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        // 绝大多数流体步进的目标是空气，先按材质短路再查数据
        if (e.getToBlock().getType().isAir()) return;
        cleanupCrop(e.getToBlock());
    }

    /** 活塞推动：被推方块自身、其上方（支撑被推走）及推入目的地若有作物，按非玩家破坏处理。 */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        BlockFace dir = e.getDirection();
        for (Block b : e.getBlocks()) {
            cleanupPistonPath(b, dir);
        }
        // 活塞头正前方：被顶替的作物（不可移动的作物不会出现在 getBlocks 里）
        cleanupPistonPath(e.getBlock().getRelative(dir), dir);
    }

    /** 活塞拉动：被拉方块自身、其上方（支撑被拉走）及拉入目的地若有作物，按非玩家破坏处理。 */
    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        BlockFace dir = e.getDirection();
        for (Block b : e.getBlocks()) {
            cleanupPistonPath(b, dir);
        }
        // 活塞头位置：粘在头上的不可移动作物被拉断
        cleanupPistonPath(e.getBlock().getRelative(dir.getOppositeFace()), dir);
    }

    /** 检查活塞推/拉路径上的一个位置及其上下文，破坏其中的作物并清数据。 */
    private static void cleanupPistonPath(Block b, BlockFace dir) {
        cleanupCrop(b);                            // 作物被直接推/拉（原版破坏但不移动）
        cleanupCrop(b.getRelative(BlockFace.UP));  // 支撑方块被移走，上方作物失去支撑
        cleanupCrop(b.getRelative(dir));           // 目的地已有作物，被推入/拉入顶替
    }

    /** 火烧作物：按非玩家破坏处理并清数据。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        cleanupCrop(e.getBlock());
    }

    /** 耕地被踩踏成泥土：上方作物失去支撑，联动破坏并清数据。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        // 方块被实体直接顶替（如铁砧/坠落方块砸中作物位置）：按非玩家破坏处理
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            breakCrop(b, crop);
            return;
        }
        if (b.getType() != Material.FARMLAND) return;
        // 耕地被踩踏成泥土：上方作物失去支撑，联动破坏并清数据
        Block above = b.getRelative(BlockFace.UP);
        SlimefunItem aboveSf = BlockStorage.check(above);
        if (aboveSf instanceof CropBlock crop) {
            breakCrop(above, crop);
        }
    }

    /**
     * 原版生长拦截：限高作物（配置 maxAge 低于原版上限，如瓜茎 maxAge=6 < 原版 7，防原版结果实）
     * 长到配置成熟年龄后取消原版生长事件——随机刻生长与骨粉催熟均走 BlockGrowEvent，
     * 作物停留在成熟阶段，无需 tick 轮询回退生长进度。
     * 附加：限高茎旁生成的原版果实（历史遗留满龄茎的掉落路径）一并取消，防刷原版西瓜/南瓜。
     */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        if (CropBlock.isClampedMaterial(b.getType())) {
            SlimefunItem sf = BlockStorage.check(b);
            if (sf instanceof CropBlock crop) {
                int clamp = crop.getClampMax();
                if (clamp >= 0 && b.getBlockData() instanceof Ageable a && a.getAge() >= clamp) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        // 果实生成事件（block=结果位，newState=瓜）：相邻存在限高茎时取消
        Material newStateType = e.getNewState().getType();
        if (newStateType == Material.MELON || newStateType == Material.PUMPKIN) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f);
                if (!CropBlock.isClampedMaterial(n.getType())) continue;
                SlimefunItem sf = BlockStorage.check(n);
                if (sf instanceof CropBlock crop && crop.getClampMax() >= 0) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    /** 破坏作物主格：掉成品/种子、清数据、清除 2 格高作物的上格残留。 */
    private static void breakCrop(Block b, CropBlock crop) {
        // 先按当前方块状态判定成熟并掉落（此时两格齐全、age 可读）。
        // 顺序与物理都关键：
        //  · 若先移除 2 格高作物的上格，原版邻块更新会把主格判为无法存活而按原版战利品破坏
        //    （泄漏瓶子草等原版物品），且主格变 AIR 后成熟判定失效、只会掉 1 个种子；
        //  · 移除两格时关闭物理（applyPhysics=false），另一半不会被原版邻块更新带掉落破坏。
        crop.onBreak(b);
        Block up = b.getRelative(BlockFace.UP);
        if (up.getType() == b.getType()) {
            up.setType(Material.AIR, false);
        }
        b.setType(Material.AIR, false);
        BlockStorage.clearBlockInfo(b);
    }

    private static void cleanupAdjacent(Block exploded) {
        cleanupCrop(exploded.getRelative(BlockFace.UP));    // 支撑被炸
        cleanupCrop(exploded.getRelative(BlockFace.DOWN));  // 2 格高上格被炸
    }

    private static void cleanupCrop(Block b) {
        SlimefunItem sf = BlockStorage.check(b);
        if (sf instanceof CropBlock crop) {
            breakCrop(b, crop);
        }
    }
}
