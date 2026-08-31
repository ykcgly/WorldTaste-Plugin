package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 酒窖多方块结构（3×3×3，y 轴从下到上，材质严格匹配不忽略方块变体）：
 * <pre>
 * 底/顶层（相同）： 橡木木板  橡木原木  橡木木板        中层： 橡木原木  橡木木板  橡木原木
 *                  橡木原木  橡木原木  橡木原木               橡木木板   空(中心)  橡木木板
 *                  橡木木板  橡木原木  橡木木板               橡木原木  [管理器] [温控器]
 * </pre>
 * 中层非旋转对称（管理器/温控器位于一条边），支持整体四个朝向放置（层内旋转 90°×4），
 * 结构校验从任一机器方块锚定，按 4 朝向逐一尝试。
 */
final class CellarStructure {

    /** 方块码。 */
    private static final byte AIR = 0;
    private static final byte PLANKS = 1;
    private static final byte LOG = 2;
    private static final byte MANAGER = 3;
    private static final byte CONTROLLER = 4;

    /** 结构布局 layout[y][z][x]，y 从下到上；中心格 (1,1,1) 为中层空气。 */
    private static final byte[][][] LAYOUT = {
        { // 底层
            {PLANKS, LOG, PLANKS},
            {LOG, LOG, LOG},
            {PLANKS, LOG, PLANKS}
        },
        { // 中层
            {LOG, PLANKS, LOG},
            {PLANKS, AIR, PLANKS},
            {LOG, MANAGER, CONTROLLER}
        },
        { // 顶层
            {PLANKS, LOG, PLANKS},
            {LOG, LOG, LOG},
            {PLANKS, LOG, PLANKS}
        }
    };

    private CellarStructure() {}

    /**
     * 校验多方块结构是否完整。
     *
     * @param anchor         锚点机器方块（管理器或温控器）
     * @param fromController 锚点是否为温控器（false = 管理器）
     */
    static boolean matches(Block anchor, boolean fromController) {
        int mx = fromController ? 1 : 0; // 基准朝向的机器偏移（相对中层中心，z=+1 边）
        int mz = 1;
        World w = anchor.getWorld();
        for (int r = 0; r < 4; r++) {
            int[] mo = rot(mx, mz, r);
            int cx = anchor.getX() - mo[0];
            int cz = anchor.getZ() - mo[1];
            int cy = anchor.getY() - 1; // 锚点位于中层
            if (checkRotation(w, cx, cy, cz, r)) return true;
        }
        return false;
    }

    /**
     * 结构完整时返回锚点机器的伙伴方块（管理器↔温控器），否则 null。
     * 供管理器 ticker 定位温控器以协调双机耗电。
     */
    static Block partner(Block anchor, boolean fromController) {
        int mx = fromController ? 1 : 0;
        int mz = 1;
        World w = anchor.getWorld();
        for (int r = 0; r < 4; r++) {
            int[] mo = rot(mx, mz, r);
            int cx = anchor.getX() - mo[0];
            int cz = anchor.getZ() - mo[1];
            int cy = anchor.getY() - 1;
            if (checkRotation(w, cx, cy, cz, r)) {
                int[] po = rot(fromController ? 0 : 1, fromController ? 1 : 1, r);
                return w.getBlockAt(cx + po[0], cy + 1, cz + po[1]);
            }
        }
        return null;
    }

    /** 按朝向 r 校验以 (cx, cy, cz) 为中层中心的结构。 */
    private static boolean checkRotation(World w, int cx, int cy, int cz, int r) {
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    byte code = LAYOUT[y][z][x];
                    int[] rxz = rot(x - 1, z - 1, r);
                    Block bl = w.getBlockAt(cx + rxz[0], cy + y, cz + rxz[1]);
                    switch (code) {
                        case AIR:
                            if (!bl.isEmpty()) return false;
                            break;
                        case PLANKS:
                            if (bl.getType() != Material.OAK_PLANKS) return false;
                            break;
                        case LOG:
                            if (bl.getType() != Material.OAK_LOG) return false;
                            break;
                        case MANAGER:
                            if (!(BlockStorage.check(bl) instanceof WineCellarManager)) return false;
                            break;
                        case CONTROLLER:
                            if (!(BlockStorage.check(bl) instanceof TemperatureController)) return false;
                            break;
                        default:
                            return false;
                    }
                }
            }
        }
        return true;
    }

    /** (x, z) 绕中心旋转 r×90°。 */
    private static int[] rot(int x, int z, int r) {
        return switch (r) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }
}
