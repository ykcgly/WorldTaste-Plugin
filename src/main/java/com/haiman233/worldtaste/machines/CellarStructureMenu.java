package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 酒窖多方块结构展示页（对齐文明复兴的多方块结构展示）：三层 3×3 网格
 * （顶层 / 中层 / 底层，y 轴从下到上），格子显示对应方块与名称，
 * 中层中心的空气格以灰色标识。返回键回到酒窖管理器页面。
 */
public final class CellarStructureMenu {

    private CellarStructureMenu() {}

    /** 打开结构展示页：cellar 为来源酒窖管理器方块（返回键直接回到该页面）。 */
    public static void open(Player p, Block cellar) {
        ChestMenu menu = new ChestMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY + "· 多方块结构");
        menu.setEmptySlotsClickable(false);
        menu.setPlayerInventoryClickable(true);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 三个 3×3 网格：列 0-2 / 3-5 / 6-8，行 2-4（每列一格子组）
        // 顶层（行 2-4 的第一组）
        layer(menu, 0, new String[][]{
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}});
        // 中层
        layer(menu, 1, new String[][]{
                {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"空气", null}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"酒窖管理器", "BREWING_STAND"}, {"温度控制器", "WAXED_COPPER_BLOCK"}});
        // 底层（与顶层相同）
        layer(menu, 2, new String[][]{
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"},
                {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"}, {"橡木原木", "OAK_LOG"},
                {"橡木木板", "OAK_PLANKS"}, {"橡木原木", "OAK_LOG"}, {"橡木木板", "OAK_PLANKS"}});

        // 层标签
        menu.addItem(1, label(Material.OAK_PLANKS, "顶层（y=+1）"), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(4, label(Material.BREWING_STAND, "中层（y=0）"), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(7, label(Material.OAK_PLANKS, "底层（y=-1）"), ChestMenuUtils.getEmptyClickHandler());

        // 返回：直接回到来源酒窖页面（子页面打开时酒窖会话已结束，不能用会话表反查）
        menu.addItem(49, back(), (pl, s, cur, action) -> {
            if (cellar != null && me.mrCookieSlime.Slimefun.api.BlockStorage.check(cellar)
                    instanceof WineCellarManager) {
                CellarMenu.open(pl, cellar);
            } else {
                pl.closeInventory();
            }
            return false;
        });
        menu.open(p);
    }

    /** 在指定 3×3 网格组（group=0/1/2，对应列 0-2/3-5/6-8，行 2-4）绘制一层结构。 */
    private static void layer(ChestMenu menu, int group, String[][] cells) {
        int baseCol = group * 3;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                String[] cell = cells[row * 3 + col];
                String name = cell[0];
                Material m = cell[1] == null ? Material.STRUCTURE_VOID : Material.matchMaterial(cell[1]);
                int slot = (row + 1) * 9 + baseCol + col;
                ItemStack it = new ItemStack(m == null ? Material.STRUCTURE_VOID : m);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GRAY + name);
                    it.setItemMeta(meta);
                }
                menu.addItem(slot, it, ChestMenuUtils.getEmptyClickHandler());
            }
        }
    }

    private static ItemStack label(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack back() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回酒窖");
            it.setItemMeta(meta);
        }
        return it;
    }
}
