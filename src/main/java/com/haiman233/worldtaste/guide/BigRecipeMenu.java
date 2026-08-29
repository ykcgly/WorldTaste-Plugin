package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 大型配方展示菜单（对齐 LogiTech 配方展示思路）：
 * <ul>
 *   <li>第 0 页 = 机器自身的合成配方（标准 3x3：材料 3-5/12-14/21-23，RecipeType 10，产物 16）；</li>
 *   <li>第 1..N 页 = 工作配方：材料区铺满 0..53（产物槽 24、机器图标 8、返回 35、翻页 53 除外），
 *       绑定槽直映、冲突/未绑定倒序补位，不再挤压配方；</li>
 *   <li>翻页合一在右下角（53）：左键下一页、右键上一页。</li>
 * </ul>
 */
public final class BigRecipeMenu {

    /** 工作配方页固定槽位。 */
    private static final int SLOT_ICON = 8;      // 右上角：机器图标
    private static final int SLOT_OUTPUT = 24;   // 产物
    private static final int SLOT_BACK = 35;     // 返回
    private static final int SLOT_PAGE = 53;     // 右下角：翻页（左键下一页/右键上一页）

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

    public static void open(Player p, WTRecipeMachine machine, int index, Runnable backOpener) {
        List<WTRecipe> recipes = machine.getRecipes();
        int workCount = recipes.size();
        int total = workCount + 1; // 0 = 合成页
        int idx = ((index % total) + total) % total;

        if (idx == 0) {
            openCraftPage(p, machine, workCount, backOpener);
        } else {
            openWorkPage(p, machine, recipes.get(idx - 1), idx - 1, workCount, backOpener);
        }
    }

    /** 合成配方页：标准 3x3 布局（对齐 LogiTech）。 */
    private static void openCraftPage(Player p, WTRecipeMachine machine, int workCount, Runnable backOpener) {
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
                SlimefunItem sf = SlimefunItem.getByItem(display);
                if (sf instanceof WTRecipeMachine sub) {
                    BigRecipeMenu.open(pl, sub, 0, () -> BigRecipeMenu.open(pl, machine, 0, backOpener));
                }
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
        // 翻页：下一页进入工作配方（左键/右键均可）
        if (workCount > 0) {
            menu.addItem(SLOT_PAGE, pageItem("下一页 · 工作配方"), (pl, s, cursor, action) -> {
                BigRecipeMenu.open(pl, machine, 1, backOpener);
                return false;
            });
        }
        menu.open(p);
    }

    /** 工作配方页：材料铺满 0..53（保留固定槽位），产物 24，图标 8，翻页 53。 */
    private static void openWorkPage(Player p, WTRecipeMachine machine, WTRecipe r, int workIdx, int workCount, Runnable backOpener) {
        String title = ChatColor.stripColor(machine.getItemName()) + " · 配方 " + (workIdx + 1) + "/" + workCount;
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
        int extra = 0;
        for (int i = 0; i < input.length; i++) {
            if (input[i] == null) continue;
            int slot = r.inSlot(i);
            if (slot < 0 || slot >= 54 || reserved[slot]) slot = -1;
            if (slot < 0) {
                for (int s = 52; s >= 0; s--) {
                    if (!reserved[s] && !used[s]) { slot = s; break; }
                }
            }
            if (slot < 0 || used[slot]) { extra++; continue; }
            used[slot] = true;
            ItemStack display = describeInput(input[i], i);
            menu.addItem(slot, display, (pl, s, cursor, action) -> {
                SlimefunItem sf = SlimefunItem.getByItem(display);
                if (sf instanceof WTRecipeMachine sub) {
                    BigRecipeMenu.open(pl, sub, 0, () -> BigRecipeMenu.open(pl, machine, workIdx + 1, backOpener));
                }
                return false;
            });
        }

        // 产物：主产物放 24（多产物在 lore 提示）
        ItemStack[] output = r.getOutput();
        ItemStack mainOut = (output.length > 0 && output[0] != null) ? output[0] : new ItemStack(Material.BARRIER);
        menu.addItem(SLOT_OUTPUT, describeOutput(mainOut, r, output.length), ChestMenuUtils.getEmptyClickHandler());

        // 机器图标（右上角）
        menu.addItem(SLOT_ICON, describeMachine(machine, r), ChestMenuUtils.getEmptyClickHandler());

        // 返回（46）
        menu.addItem(SLOT_BACK, backItem(backOpener == null), (pl, s, cursor, action) -> {
            if (backOpener != null) backOpener.run();
            else JegHook.openGuide(pl);
            return false;
        });

        // 翻页合一（53）：左键下一页、右键上一页；单配方时为返回
        if (workCount > 1) {
            ItemStack pageBtn = pageItem("配方 " + (workIdx + 1) + "/" + workCount);
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
                if (action.isRightClicked()) {
                    BigRecipeMenu.open(pl, machine, workIdx == 0 ? 0 : workIdx, backOpener);
                } else {
                    BigRecipeMenu.open(pl, machine, workIdx + 2, backOpener);
                }
                return false;
            });
        } else {
            menu.addItem(SLOT_PAGE, backItem(backOpener == null), (pl, s, cursor, action) -> {
                if (backOpener != null) backOpener.run();
                else JegHook.openGuide(pl);
                return false;
            });
        }

        menu.open(p);
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
            lore.add(ChatColor.DARK_GRAY + "点击查看该材料配方");
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