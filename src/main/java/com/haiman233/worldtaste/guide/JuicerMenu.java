package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 榨汁盆配方展示菜单（布局对齐需求图，菜单代码参考终焉厨锅大配方展示）：
 * <ul>
 *   <li>固定配方页：背景为粘液 UI，绿/黄玻璃板装饰；红色区展示投入材料（多数量直接
 *       体现在物品堆叠数上），右侧产出并标注产出份数；底排铁砧=榨汁方式（踩踏/砸击）、
 *       重锤=所需次数（数量即次数）、附魔金苹果=混合材料页入口、门=返回指南、
 *       箭矢=翻页（左键下一页、右键上一页）；</li>
 *   <li>混合材料页：粉玻璃板描边，首排中间为「这些都能榨汁哦」附魔金苹果标签，
 *       其余空位铺可混合材料，底排门/箭矢同上。</li>
 * </ul>
 */
public final class JuicerMenu {

    /** 固定配方页槽位。 */
    private static final int[] INGREDIENT_SLOTS = {20, 21, 22, 29, 30, 31}; // 红色区
    private static final int SLOT_OUTPUT = 34;   // 右侧产出
    private static final int SLOT_TYPE = 47;     // 榨汁方式（踩踏/铁砧）
    private static final int SLOT_COUNT = 49;    // 重锤：所需次数
    private static final int SLOT_MIX = 51;      // 混合材料页入口
    private static final int SLOT_BACK = 52;     // 门：返回指南
    private static final int SLOT_PAGE = 53;     // 箭矢：翻页

    /** 混合材料页槽位。 */
    private static final int SLOT_MIX_LABEL = 4;  // 首排中间标签（顶排上移一格，替换粉玻璃板）
    private static final int MIX_PAGE_SIZE = 28;  // 每页材料数（内部空位）

    private JuicerMenu() {}

    /**
     * 榨汁盆自身配方概览页（JEG 指南点击榨汁盆时替代默认展示）：标准 3x3 展示其合成配方
     * （当前未配置则留空），右下角金苹果按钮进入配方展示页。
     */
    public static void openBasinOverview(Player p) {
        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY + "· 自身配方");
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem basin =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(
                        com.haiman233.worldtaste.machines.JuicerBasin.ITEM_ID);
        if (basin == null) return;

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = basin.getRecipe();
        boolean any = false;
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            any = true;
            ItemStack display = craft[i].clone();
            menu.addItem(craftSlots[i], display, ChestMenuUtils.getEmptyClickHandler());
        }
        if (!any) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta nm = none.getItemMeta();
            if (nm != null) {
                nm.setDisplayName(ChatColor.YELLOW + "自身配方待定");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "该机器暂未配置合成配方");
                nm.setLore(lore);
                none.setItemMeta(nm);
            }
            menu.addItem(13, none, ChestMenuUtils.getEmptyClickHandler());
        }
        // 机器图标 + 产物位
        ItemStack icon = basin.getItem().clone();
        ItemMeta im = icon.getItemMeta();
        if (im != null) {
            List<String> lore = im.getLore() == null ? new ArrayList<>() : new ArrayList<>(im.getLore());
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "机器本体");
            im.setLore(lore);
            icon.setItemMeta(im);
        }
        menu.addItem(16, icon, ChestMenuUtils.getEmptyClickHandler());
        // 返回指南 / 右下角金苹果进入配方展示
        menu.addItem(35, backItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(53, entryButton(), (pl, s, cursor, action) -> {
            openRecipes(pl, 0);
            return false;
        });
        menu.open(p);
    }

    /** 金苹果入口按钮（带 PDC 标记，指南注入与概览页共用）。 */
    public static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "榨汁盆配方");
            meta.getPersistentDataContainer().set(JuicerGuideListener.KEY_ENTRY,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 固定配方页：每页展示一个配方，箭矢翻页。 */
    public static void openRecipes(Player p, int index) {
        List<JuicerRecipe> recipes = JuicerRecipe.all();
        if (recipes.isEmpty()) {
            openMix(p, 0);
            return;
        }
        int idx = ((index % recipes.size()) + recipes.size()) % recipes.size();
        JuicerRecipe r = recipes.get(idx);

        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY
                + "· 配方 " + (idx + 1) + "/" + recipes.size());
        fillRecipeDeco(menu);

        // 红色区：投入材料（数量体现为物品堆叠数）
        int i = 0;
        for (Map.Entry<String, Integer> en : r.ingredientRefs.entrySet()) {
            if (i >= INGREDIENT_SLOTS.length) break;
            menu.addItem(INGREDIENT_SLOTS[i], describeIngredient(en.getKey(), en.getValue()),
                    ChestMenuUtils.getEmptyClickHandler());
            i++;
        }
        // 右侧产出：标注产出份数
        menu.addItem(SLOT_OUTPUT, describeOutput(r), ChestMenuUtils.getEmptyClickHandler());
        // 榨汁方式 + 所需次数
        menu.addItem(SLOT_TYPE, r.playerType && r.anvilType ? typeBoth()
                : r.anvilType ? typeAnvil() : typeStomp(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_COUNT, countItem(r.progress), ChestMenuUtils.getEmptyClickHandler());
        // 混合材料页入口（未配置 mix 段时不显示）+ 返回指南 + 翻页
        if (JuicerRecipe.mix != null) {
            menu.addItem(SLOT_MIX, mixEntry(), (pl, s, cursor, action) -> {
                openMix(pl, 0);
                return false;
            });
        }
        menu.addItem(SLOT_BACK, backItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openRecipes(pl, action.isRightClicked() ? idx - 1 : idx + 1);
            return false;
        });
        menu.open(p);
    }

    /** 混合材料页：粉框 + 标签 + 可混合材料（翻页）。 */
    public static void openMix(Player p, int index) {
        List<ItemStack> mats = new ArrayList<>();
        if (JuicerRecipe.mix != null) {
            for (String ref : JuicerRecipe.mix.mixableRefs) {
                ItemStack it = JuicerRecipe.refToItem(ref);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                    lore.add("");
                    lore.add(ChatColor.GRAY + "可投入榨汁盆混合榨汁");
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
                mats.add(it);
            }
        }
        int pages = Math.max(1, (mats.size() + MIX_PAGE_SIZE - 1) / MIX_PAGE_SIZE);
        int idx = ((index % pages) + pages) % pages;

        ChestMenu menu = baseMenu(ChatColor.GOLD + "榨汁盆 " + ChatColor.GRAY
                + "· 混合材料 " + (idx + 1) + "/" + pages);
        fillPinkDeco(menu);

        // 首排中间：标签
        ItemStack label = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        ItemMeta lm = label.getItemMeta();
        if (lm != null) {
            lm.setDisplayName(Colors.c("&e这&b些&2都&a能&6榨&d汁&e哦"));
            label.setItemMeta(lm);
        }
        menu.addItem(SLOT_MIX_LABEL, label, ChestMenuUtils.getEmptyClickHandler());

        // 内部空位：可混合材料（每页 27 个）
        int[] slots = interiorSlots();
        for (int i = 0; i < MIX_PAGE_SIZE; i++) {
            int pageIdx = idx * MIX_PAGE_SIZE + i;
            if (pageIdx >= mats.size()) break;
            menu.addItem(slots[i], mats.get(pageIdx), ChestMenuUtils.getEmptyClickHandler());
        }

        menu.addItem(SLOT_BACK, backItem(), (pl, s, cursor, action) -> {
            JegHook.openGuide(pl);
            return false;
        });
        menu.addItem(SLOT_PAGE, pageItem(), (pl, s, cursor, action) -> {
            openMix(pl, action.isRightClicked() ? idx - 1 : idx + 1);
            return false;
        });
        menu.open(p);
    }

    /** 内部材料槽位（行 1-4 × 列 1-7）。 */
    private static int[] interiorSlots() {
        int[] slots = new int[MIX_PAGE_SIZE];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[i++] = row * 9 + col;
            }
        }
        return slots;
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

    /** 固定配方页装饰：绿/黄玻璃板环绕红色配方区（对齐需求图）。 */
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

    /** 混合材料页装饰：粉玻璃板描边。 */
    private static void fillPinkDeco(ChestMenu menu) {
        for (int col = 0; col < 9; col++) {
            if (col == SLOT_MIX_LABEL) continue; // 顶排中间由标签占据
            pane(menu, col, Material.PINK_STAINED_GLASS_PANE);
            pane(menu, 45 + col, Material.PINK_STAINED_GLASS_PANE);
        }
        for (int row = 1; row <= 4; row++) {
            pane(menu, row * 9, Material.PINK_STAINED_GLASS_PANE);
            pane(menu, row * 9 + 8, Material.PINK_STAINED_GLASS_PANE);
        }
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

    /** 投入材料：数量体现为堆叠数并写明。 */
    private static ItemStack describeIngredient(String ref, int count) {
        ItemStack it = JuicerRecipe.refToItem(ref);
        it.setAmount(Math.min(64, Math.max(1, count)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "投入数量: " + ChatColor.YELLOW + count);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 产出：lore 标注榨好后可接取的份数。 */
    private static ItemStack describeOutput(JuicerRecipe r) {
        ItemStack it = r.result.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.GRAY + "产出份数: " + ChatColor.YELLOW + r.yield + " 份");
            lore.add(ChatColor.GRAY + "瓶子每次接取 1 份，桶需满盆一次接完");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 榨汁方式：玩家踩踏（跳跃）或铁砧砸击。 */
    private static ItemStack typeStomp() {
        ItemStack it = new ItemStack(Material.LEATHER_BOOTS);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "榨汁方式: 玩家踩踏");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "站在盆内跳跃踩踏（每次 +1）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack typeAnvil() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "榨汁方式: 铁砧砸击");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "铁砧砸在盆顶（每次 +4）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 双压榨方式：踩踏与铁砧砸击均可（铁砧图标 + 双行说明）。 */
    private static ItemStack typeBoth() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "榨汁方式: 踩踏 / 铁砧砸击");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "站在盆内跳跃踩踏（每次 +1）");
            lore.add(ChatColor.GRAY + "或铁砧砸在盆顶（每次 +4）");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 重锤：数量即所需榨汁次数。 */
    private static ItemStack countItem(int progress) {
        ItemStack it = new ItemStack(Material.MACE);
        it.setAmount(Math.min(64, Math.max(1, progress)));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "所需次数");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "踩踏/砸击 " + ChatColor.YELLOW + progress + ChatColor.GRAY + " 次");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack mixEntry() {
        ItemStack it = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "混合榨汁材料");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "查看哪些材料可以混合榨汁");
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "点击打开");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack backItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回指南");
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
