package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 大型配方展示菜单（对齐 LogiTech 配方展示思路）：
 * <ul>
 *   <li>第 0 页 = 机器自身的合成配方（标准 3x3：材料 3-5/12-14/21-23，RecipeType 10，产物 16）；
 *       右下角（53）为「配方展示」按钮——以机器配方补全同款形式列出该机器全部产物，点击产物
 *       进入其专属的大型配方展示页；</li>
 *   <li>产物列表页 = 每个产物一格（0..44，按主产物去重），45 返回合成配方页、53 翻页；</li>
 *   <li>产物配方页 = 大型配方展示：材料区铺满 0..53（产物槽 24、机器图标 8、返回 35、翻页 53 除外），
 *       绑定槽直映、冲突/未绑定倒序补位；翻页在该产物的配方集合内循环，返回 35 回产物列表；</li>
 *   <li>所有粘液材料点击可跳转到对应材料的指南页（依赖配方堆携带 slimefun id PDC，
 *       见 {@code Read.resolve} 的前向引用修复）。</li>
 * </ul>
 */
public final class BigRecipeMenu {

    /** 工作配方页固定槽位。 */
    private static final int SLOT_ICON = 8;      // 右上角：机器图标
    private static final int SLOT_OUTPUT = 24;   // 产物
    private static final int SLOT_BACK = 35;     // 返回
    private static final int SLOT_PAGE = 53;     // 右下角：翻页 / 配方展示入口

    private BigRecipeMenu() {}

    /** 是否为大型配方机器：任一工作配方的输入项数超过 9（3x3 标准以上）。
     *  大型机器使用本大配方菜单；普通机器保留 JEG/Slimefun 默认配方展示。 */
    public static boolean isLargeRecipeMachine(WTRecipeMachine machine) {
        for (WTRecipe r : machine.getRecipes()) {
            int n = 0;
            for (ItemStack in : r.getInput()) if (in != null) n++;
            if (n > 9) return true;
        }
        return false;
    }

    /** 兼容入口：0=合成配方页；i=按全局顺序的第 i 个工作配方页（不经产物列表）。 */
    public static void open(Player p, WTRecipeMachine machine, int index, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workCount = recipes.size();
        int total = workCount + 1; // 0 = 合成页
        int idx = ((index % total) + total) % total;

        if (idx == 0) {
            openCraftPage(p, machine, backOpener);
        } else {
            int[] all = new int[workCount];
            for (int i = 0; i < workCount; i++) all[i] = i;
            openWorkPage(p, machine, all, idx - 1, false, backOpener);
        }
    }

    /** 合成配方页：标准 3x3 布局（对齐 LogiTech），53 = 配方展示（产物列表）。 */
    private static void openCraftPage(Player p, WTRecipeMachine machine, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workCount = recipes.size();
        String title = ChatColor.stripColor(machine.getItemName()) + " · 合成配方";
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        int[] craftSlots = {3, 4, 5, 12, 13, 14, 21, 22, 23};
        ItemStack[] craft = machine.getRecipe();
        for (int i = 0; i < 9 && i < craft.length; i++) {
            if (craft[i] == null) continue;
            ItemStack display = describeCraftInput(craft[i], i);
            menu.addItem(craftSlots[i], display, (pl, s, cursor, action) -> {
                navigateIngredient(pl, display, () -> openCraftPage(pl, machine, backOpener));
                return false;
            });
        }
        ItemStack rtIcon = machine.getRecipeType().toItem();
        menu.addItem(10, rtIcon != null ? rtIcon : ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(16, describeMachine(machine, null), ChestMenuUtils.getEmptyClickHandler());
        menu.addItem(SLOT_ICON, infoItem("合成配方", "使用 " + recipeTypeName(machine) + " 合成该机器"), ChestMenuUtils.getEmptyClickHandler());
        // 返回（左下）
        menu.addItem(35, backItem(backOpener == null), (pl, s, cursor, action) -> {
            if (backOpener != null) backOpener.run();
            else JegHook.openGuide(pl);
            return false;
        });
        // 右下角：配方展示（产物列表，配方补全同款形式）
        if (workCount > 0) {
            menu.addItem(SLOT_PAGE, productsButton(workCount), (pl, s, cursor, action) -> {
                openProductList(pl, machine, 0, backOpener);
                return false;
            });
        }
        menu.open(p);
    }

    /** 产物列表页（配方补全同款形式）：主产物去重后逐格展示，点击进入该产物的配方页。 */
    private static void openProductList(Player p, WTRecipeMachine machine, int page, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        // 按主产物身份去重（SF id 优先，否则材质+显示名），记录每产物命中的配方下标
        Map<String, ItemStack> products = new LinkedHashMap<>();
        Map<String, List<Integer>> indices = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            ItemStack[] out = recipes.get(i).getOutput();
            ItemStack main = (out.length > 0 && out[0] != null) ? out[0] : null;
            if (main == null) continue;
            String key = productKey(main);
            products.putIfAbsent(key, main);
            indices.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<String> keys = new ArrayList<>(products.keySet());
        if (keys.isEmpty()) {
            open(p, machine, 0, backOpener);
            return;
        }

        int perPage = 45; // 0..44 放产物，45 返回，53 翻页
        int pages = (keys.size() + perPage - 1) / perPage;
        int pg = ((page % pages) + pages) % pages;

        String title = ChatColor.stripColor(machine.getItemName()) + " · 产物配方 " + (pg + 1) + "/" + pages;
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i = 0; i < perPage; i++) {
            int k = pg * perPage + i;
            if (k >= keys.size()) break;
            String key = keys.get(k);
            ItemStack out = products.get(key);
            List<Integer> idxs = indices.get(key);
            menu.addItem(i, productEntry(out, idxs.size()), (pl, s, cursor, action) -> {
                int[] arr = new int[idxs.size()];
                for (int j = 0; j < arr.length; j++) arr[j] = idxs.get(j);
                openWorkPage(pl, machine, arr, 0, true, backOpener);
                return false;
            });
        }
        // 返回合成配方页（45）
        menu.addItem(45, backItem(false), (pl, s, cursor, action) -> {
            openCraftPage(pl, machine, backOpener);
            return false;
        });
        // 翻页（53）
        if (pages > 1) {
            ItemStack pageBtn = pageItem("翻页 " + (pg + 1) + "/" + pages);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左键：下一页");
                lore.add(ChatColor.GRAY + "右键：上一页");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            menu.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                openProductList(pl, machine, action.isRightClicked() ? pg - 1 : pg + 1, backOpener);
                return false;
            });
        }
        menu.open(p);
    }

    /** 大型配方展示页：cycle = 可循环展示的配方下标（全局顺序），fromList = 是否从产物列表进入。 */
    private static void openWorkPage(Player p, WTRecipeMachine machine, int[] cycle, int pos, boolean fromList, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workIdx = cycle[pos];
        WTRecipe r = recipes.get(workIdx);
        int workCount = recipes.size();
        String title = ChatColor.stripColor(machine.getItemName()) + " · 配方 " + (pos + 1) + "/" + cycle.length;
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 固定槽位
        boolean[] reserved = new boolean[54];
        reserved[SLOT_ICON] = reserved[SLOT_OUTPUT] = reserved[SLOT_BACK] = reserved[SLOT_PAGE] = true;

        // 材料区：绑定槽直映（跳过固定槽），未绑定/冲突从 52 倒序补位
        ItemStack[] input = r.getInput();
        boolean[] used = new boolean[54];
        for (int i = 0; i < input.length; i++) {
            if (input[i] == null) continue;
            int slot = r.inSlot(i);
            if (slot < 0 || slot >= 54 || reserved[slot]) slot = -1;
            if (slot < 0) {
                for (int s = 52; s >= 0; s--) {
                    if (!reserved[s] && !used[s]) { slot = s; break; }
                }
            }
            if (slot < 0 || used[slot]) continue;
            used[slot] = true;
            ItemStack display = describeInput(input[i], i);
            menu.addItem(slot, display, (pl, s, cursor, action) -> {
                navigateIngredient(pl, display, () -> openWorkPage(pl, machine, cycle, pos, fromList, backOpener));
                return false;
            });
        }

        // 产物：主产物放 24（多产物在 lore 提示）
        ItemStack[] output = r.getOutput();
        ItemStack mainOut = (output.length > 0 && output[0] != null) ? output[0] : new ItemStack(Material.BARRIER);
        menu.addItem(SLOT_OUTPUT, describeOutput(mainOut, r, output.length), ChestMenuUtils.getEmptyClickHandler());

        // 机器图标（右上角）
        menu.addItem(SLOT_ICON, describeMachine(machine, r), ChestMenuUtils.getEmptyClickHandler());

        // 返回（46）：从产物列表进入 → 回产物列表；否则回上级/指南
        menu.addItem(SLOT_BACK, backItem(backOpener == null && !fromList), (pl, s, cursor, action) -> {
            if (fromList) openProductList(pl, machine, 0, backOpener);
            else if (backOpener != null) backOpener.run();
            else JegHook.openGuide(pl);
            return false;
        });

        // 翻页（53）：在 cycle 内循环；单配方时为返回键
        if (cycle.length > 1) {
            ItemStack pageBtn = pageItem("配方 " + (pos + 1) + "/" + cycle.length);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左键：下一页");
                lore.add(ChatColor.GRAY + "右键：上一页");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            menu.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                int next = action.isRightClicked() ? (pos - 1 + cycle.length) % cycle.length : (pos + 1) % cycle.length;
                openWorkPage(pl, machine, cycle, next, fromList, backOpener);
                return false;
            });
        } else {
            menu.addItem(SLOT_PAGE, backItem(backOpener == null && !fromList), (pl, s, cursor, action) -> {
                if (fromList) openProductList(pl, machine, 0, backOpener);
                else if (backOpener != null) backOpener.run();
                else JegHook.openGuide(pl);
                return false;
            });
        }

        menu.open(p);
    }

    /** 材料点击导航：子机器 → 打开其大配方菜单；普通粘液物品 → 跳转指南材料页；原版材料不动。 */
    private static void navigateIngredient(Player pl, ItemStack display, Runnable reopenSelf) {
        SlimefunItem sf = SlimefunItem.getByItem(display);
        if (sf instanceof WTRecipeMachine sub) {
            BigRecipeMenu.open(pl, sub, 0, reopenSelf);
        } else if (sf != null) {
            // 材料堆带 slimefun id PDC（Read.resolve 前向引用修复后所有配方材料均可识别）；
            // 经 facade 派发到当前指南实现（JEG 安装时即 JEG 页面），addToHistory 支持返回
            PlayerProfile.get(pl, profile -> SlimefunGuide.displayItem(profile, sf, true));
        }
        // 原版材料：无粘液页可跳，菜单保持不动
    }

    /** 产物身份键：SF id 优先，否则材质+显示名。 */
    private static String productKey(ItemStack out) {
        SlimefunItem sf = SlimefunItem.getByItem(out);
        if (sf != null) return "sf:" + sf.getId();
        String name = out.hasItemMeta() && out.getItemMeta().hasDisplayName()
                ? out.getItemMeta().getDisplayName() : "";
        return "mc:" + out.getType() + ":" + name;
    }

    private static String recipeTypeName(WTRecipeMachine machine) {
        try {
            return ChatColor.stripColor(machine.getRecipeType().toItem().getItemMeta().getDisplayName());
        } catch (Throwable t) {
            return machine.getRecipeType().getKey().getKey();
        }
    }

    private static ItemStack describeInput(ItemStack in, int index) {
        ItemStack clone = in.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "材料 " + (index + 1));
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "数量: " + clone.getAmount());
            SlimefunItem sf = SlimefunItem.getByItem(clone);
            if (sf instanceof WTRecipeMachine) lore.add(ChatColor.DARK_GRAY + "点击打开该机器配方");
            else if (sf != null) lore.add(ChatColor.DARK_GRAY + "点击查看获取方式");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    private static ItemStack describeCraftInput(ItemStack in, int index) {
        ItemStack clone = in.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "合成材料 " + (index + 1));
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "数量: " + clone.getAmount());
            SlimefunItem sf = SlimefunItem.getByItem(clone);
            if (sf instanceof WTRecipeMachine) lore.add(ChatColor.DARK_GRAY + "点击打开该机器配方");
            else if (sf != null) lore.add(ChatColor.DARK_GRAY + "点击查看获取方式");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /** 产物列表条目：概率/数量 lore + 配方数提示。 */
    private static ItemStack productEntry(ItemStack out, int recipeCount) {
        ItemStack clone = out.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "产物");
            lore.add(ChatColor.GRAY + "共有 " + recipeCount + " 个配方");
            lore.add(ChatColor.YELLOW + "点击查看配方");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /** 主产物展示：概率/数量 lore，多产物在 lore 提示其余。 */
    private static ItemStack describeOutput(ItemStack out, WTRecipe r, int totalOutputs) {
        ItemStack clone = out.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "产物 1");
            int ch = r.chance(0);
            if (ch < 100) lore.add(ChatColor.YELLOW + "概率: " + ch + "%");
            if (clone.getAmount() > 1) lore.add(ChatColor.RED + "数量: " + clone.getAmount());
            if (totalOutputs > 1) lore.add(ChatColor.DARK_GRAY + "另有 " + (totalOutputs - 1) + " 个产物");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    private static ItemStack describeMachine(WTRecipeMachine machine, WTRecipe r) {
        ItemStack icon = machine.getItem().clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            if (r != null) {
                lore.add(ChatColor.GRAY + "耗时: " + (r.getTicks() / 2) + "s");
                lore.add(ChatColor.DARK_GRAY + "在该机器中制作");
            } else {
                lore.add(ChatColor.DARK_GRAY + "机器本体（合成产物）");
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack infoItem(String name, String desc) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + desc);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 合成配方页右下角按钮：进入产物列表（配方补全同款形式）。 */
    private static ItemStack productsButton(int workCount) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "配方展示");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "按产物浏览全部工作配方");
            lore.add(ChatColor.GRAY + "当前共 " + workCount + " 个配方");
            lore.add("");
            lore.add(ChatColor.YELLOW + "点击打开产物列表");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pageItem(String name) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack backItem(boolean toGuide) {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + (toGuide ? "返回指南" : "返回"));
            it.setItemMeta(meta);
        }
        return it;
    }
}
