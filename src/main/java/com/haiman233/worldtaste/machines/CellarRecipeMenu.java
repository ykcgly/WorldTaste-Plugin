package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.jeg.JegHook;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 酒窖配方展示菜单（布局与榨汁盆配方页一致，无混合材料页）：
 * <ul>
 *   <li>概览页：酒窖管理器自身合成配方（标准 3x3，未配置则提示待定），右下角书本按钮
 *       进入配方展示页，门=返回指南；</li>
 *   <li>配方页：背景为粘液 UI，绿/黄玻璃板环绕红色配方区展示投入材料（份数体现为堆叠数），
 *       右侧单位产物并标注产出份数（= 投入总份数）；底排酿造台=酿造于酒窖、时钟=酿造时长、
 *       烈焰粉/恶魂之泪=是否可陈酿、门=返回（从机器进入时返回酒窖页面，否则返回指南）、
 *       箭矢=翻页（左键下一页、右键上一页）。</li>
 * </ul>
 */
public final class CellarRecipeMenu {

    /** 配方页固定槽位。 */
    private static final int[] INGREDIENT_SLOTS = {20, 21, 22, 29, 30, 31}; // 红色区
    private static final int SLOT_OUTPUT = 34;   // 右侧产出
    private static final int SLOT_TYPE = 47;     // 酿造台：酿造于酒窖
    private static final int SLOT_DURATION = 49; // 时钟：酿造时长
    private static final int SLOT_AGING = 51;    // 是否可陈酿
    private static final int SLOT_BACK = 52;     // 门：返回
    private static final int SLOT_PAGE = 53;     // 箭矢：翻页

    private CellarRecipeMenu() {}

    /** 酒窖管理器自身配方概览页（指南/机器入口），右下角书本按钮进入配方展示页。 */
    public static void openOverview(Player p) {
        ChestMenu menu = baseMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY + "· 自身配方");
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem manager =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById("WT_JIUJIAO");
        if (manager == null) return;

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = manager.getRecipe();
        boolean any = false;
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            any = true;
            menu.addItem(craftSlots[i], craft[i].clone(), ChestMenuUtils.getEmptyClickHandler());
        }
        if (!any) {
            menu.addItem(13, noneItem(), ChestMenuUtils.getEmptyClickHandler());
        }
        // 机器图标 + 产物位
        menu.addItem(16, machineIcon(manager), ChestMenuUtils.getEmptyClickHandler());
        // 返回指南 / 右下角书本进入配方展示
        menu.addItem(35, backToGuideItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(53, entryButton(), (pl, s, cursor, action) -> {
            openRecipes(pl, 0, null);
            return false;
        });
        menu.open(p);
    }

    /** 书本入口按钮（带 PDC 标记，指南注入与概览页共用）。 */
    public static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "酒窖配方");
            meta.getPersistentDataContainer().set(
                    com.haiman233.worldtaste.guide.CellarGuideListener.KEY_ENTRY,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * 固定配方页：每页展示一个配方，箭矢翻页。
     * cellar 为来源酒窖管理器方块（从酒窖页面进入时返回键回到该页面；null = 从指南进入）。
     */
    public static void openRecipes(Player p, int index, Block cellar) {
        List<CellarRecipe> recipes = CellarRecipe.all();
        if (recipes.isEmpty()) {
            p.sendMessage("§c暂无酒窖配方（cellar.yml）。");
            return;
        }
        int idx = ((index % recipes.size()) + recipes.size()) % recipes.size();
        CellarRecipe r = recipes.get(idx);

        ChestMenu menu = baseMenu(ChatColor.GOLD + "酒窖管理器 " + ChatColor.GRAY
                + "· 配方 " + (idx + 1) + "/" + recipes.size());
        fillRecipeDeco(menu);

        // 红色区：投入材料（份数体现为堆叠数）
        int i = 0;
        for (Map.Entry<String, Integer> en : r.ingredientRefs.entrySet()) {
            if (i >= INGREDIENT_SLOTS.length) break;
            menu.addItem(INGREDIENT_SLOTS[i], describeIngredient(en.getKey(), en.getValue()),
                    ChestMenuUtils.getEmptyClickHandler());
            i++;
        }
        // 右侧产出：标注产出份数
        menu.addItem(SLOT_OUTPUT, describeOutput(r), ChestMenuUtils.getEmptyClickHandler());
        // 酿造于酒窖 + 酿造时长 + 陈酿标识
        menu.addItem(SLOT_TYPE, typeItem(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_DURATION, durationItem(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_AGING, agingIcon(r.aging), ChestMenuUtils.getEmptyClickHandler());
        // 返回：从酒窖页面进入时返回酒窖，否则返回指南（子页面打开时酒窖会话已结束，
        // 用打开时捕获的方块而不是会话表反查）；翻页
        menu.addItem(SLOT_BACK, backItem(cellar != null), (pl, s, cursor, action) -> {
            if (cellar != null && me.mrCookieSlime.Slimefun.api.BlockStorage.check(cellar)
                    instanceof WineCellarManager) {
                CellarMenu.open(pl, cellar);
            } else {
                JegHook.openGuide(pl);
            }
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipes(pl, action.isRightClicked() ? idx - 1 : idx + 1, cellar);
            return false;
        });
        menu.open(p);
    }

    private static ChestMenu baseMenu(String title) {
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        menu.setPlayerInventoryClickable(true); // 允许拿取背包内物品
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        return menu;
    }

    /** 配方页装饰：绿/黄玻璃板环绕红色配方区（与榨汁盆配方页一致）。 */
    private static void fillRecipeDeco(ChestMenu menu) {
        pane(menu, 10, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 11, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 12, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 13, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 14, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 19, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 20, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 21, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 22, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 23, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 28, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 29, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 30, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 31, Material.RED_STAINED_GLASS_PANE);
        pane(menu, 32, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 37, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 38, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 39, Material.GREEN_STAINED_GLASS_PANE);
        pane(menu, 40, Material.YELLOW_STAINED_GLASS_PANE);
        pane(menu, 41, Material.GREEN_STAINED_GLASS_PANE);
    }

    private static void pane(ChestMenu menu, int slot, Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§r");
            it.setItemMeta(meta);
        }
        menu.addItem(slot, it, ChestMenuUtils.getEmptyClickHandler());
    }

    /** 投入材料：份数体现为堆叠数并写明。 */
    private static ItemStack describeIngredient(String ref, int count) {
        ItemStack it = JuicerRecipe.refToItem(ref);
        it.setAmount(Math.min(64, Math.max(1, count)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "投入份数: " + ChatColor.YELLOW + count);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 产出：lore 标注产出份数与陈酿标识。 */
    private static ItemStack describeOutput(CellarRecipe r) {
        ItemStack it = r.result.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "产出份数: " + ChatColor.YELLOW + r.totalInput + " 单位");
            lore.add(ChatColor.GRAY + "每单位产出灌装为 1 瓶");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 酿造台：酿造于酒窖管理器。 */
    private static ItemStack typeItem() {
        ItemStack it = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "酿造于酒窖管理器");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "投入果汁（清水可稀释）+ 酒曲后启动酿造");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 时钟：酿造时长（正式版 20~40 分钟，当前测试模式立即完成）。 */
    private static ItemStack durationItem() {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "酿造时长");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "20~40 分钟（随机）");
            lore.add(ChatColor.GRAY + "完成后可陈化或直接灌装出酒");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 陈酿标识：烈焰粉=可陈酿 / 恶魂之泪=不可陈酿。 */
    private static ItemStack agingIcon(boolean aging) {
        ItemStack it = new ItemStack(aging ? Material.BLAZE_POWDER : Material.GHAST_TEAR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(aging ? ChatColor.RED + "允许陈酿" : ChatColor.GRAY + "不可陈酿");
            List<String> lore = new ArrayList<>();
            lore.add(aging ? ChatColor.GRAY + "陈化模式下酒精度随游戏日增长"
                    : ChatColor.GRAY + "该产物无酒精度，不可陈化");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack machineIcon(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem manager) {
        ItemStack icon = manager.getItem().clone();
        ItemMeta im = icon.getItemMeta();
        if (im != null) {
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "机器本体");
            im.setLore(lore);
            icon.setItemMeta(im);
        }
        return icon;
    }

    private static ItemStack noneItem() {
        ItemStack none = new ItemStack(Material.BARRIER);
        ItemMeta nm = none.getItemMeta();
        if (nm != null) {
            nm.setDisplayName(ChatColor.YELLOW + "自身配方待定");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "该机器暂未配置合成配方");
            nm.setLore(lore);
            none.setItemMeta(nm);
        }
        return none;
    }

    private static ItemStack backToGuideItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回指南");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 门：从酒窖页面进入时返回酒窖（文案随场景），否则返回指南。 */
    private static ItemStack backItem(boolean fromCellar) {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + (fromCellar ? "返回酒窖" : "返回指南"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pageItem() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "翻页");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "左键：下一页");
            lore.add(ChatColor.GRAY + "右键：上一页");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
