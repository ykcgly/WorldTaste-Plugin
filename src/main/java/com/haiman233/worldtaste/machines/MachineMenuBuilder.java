package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import com.haiman233.worldtaste.util.Lang;

/**
 * 配方机器菜单构建（静态参数化，电力机器 {@link WTRecipeMachine} 与纯工作台
 * {@link WTWorkbench} 两族共用——原为 WTRecipeMachine 私有逻辑，工作台脱离 AContainer
 * 继承链后提取至此）。
 *
 * <p>布局规则：menus.yml 装饰槽 → 功能槽（输入/输出/进度）外全部背景填充 → 配方补全按钮
 * （知识之书，尺寸自适应挑选空位）→ 输出槽「只许取出」限制。尺寸按最高功能/装饰槽
 * 向上取整到 9 的倍数（≤54），防止 BlockMenuPreset 自动定尺寸漏盖功能槽。</p>
 */
public final class MachineMenuBuilder {

    private MachineMenuBuilder() {}

    /**
     * 构建菜单。
     *
     * @param preset        目标 preset
     * @param machine       所属机器（配方补全按钮的打开目标）
     * @param menu          menus.yml 菜单定义（null = 无装饰）
     * @param inputSlots    输入槽
     * @param outputSlots   输出槽
     * @param progressSlot  进度槽（-1 = 无进度显示，纯工作台）
     * @param extra         额外可交互槽位（模板机器的模板槽等，参与背景填充豁免与尺寸计算）
     */
    public static void build(BlockMenuPreset preset, WTMachine machine, MenuDef menu,
                             int[] inputSlots, int[] outputSlots,
                             int progressSlot, ItemStack progressBar, java.util.Set<Integer> extra) {
        Set<Integer> placed = new HashSet<>();
        // 装饰（来自 menus.yml）
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }
        // 功能槽
        Set<Integer> functional = new HashSet<>();
        for (int s : inputSlots) functional.add(s);
        for (int s : outputSlots) functional.add(s);
        if (progressSlot >= 0) functional.add(progressSlot);
        // 尺寸必须覆盖所有功能槽与装饰槽：BlockMenuPreset 按「已放置物品」自动定尺寸，
        // 而输出槽只挂 click handler、不放置物品。按最大槽位向上取整到 9 的倍数（≤54）。
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
        for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
        if (progressSlot >= 0) maxSlot = Math.max(maxSlot, progressSlot);
        for (int s : extra) maxSlot = Math.max(maxSlot, s);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i) && !extra.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }
        // 配方补全按钮（自定义精确补全；JEG Build 205 对绑定槽配方存在循环左移缺陷，不采用）
        int fillSlot = pickFillSlot(functional, placed, extra);
        if (fillSlot >= 0) {
            preset.addItem(fillSlot, fillButton(), (player, s, cursor, action) -> {
                me.mrCookieSlime.Slimefun.api.inventory.BlockMenu bm = null;
                // InventoryView 在 1.21 由类改为接口，经 Views.top 反射工具取顶部容器
                org.bukkit.inventory.Inventory openTop =
                        com.haiman233.worldtaste.util.Views.top(player.getOpenInventory());
                if (openTop != null && openTop.getHolder()
                        instanceof me.mrCookieSlime.Slimefun.api.inventory.BlockMenu blockMenu) {
                    bm = blockMenu;
                }
                if (action.isRightClicked()) {
                    // 右键/Shift+右键：一键补全「上一次补全的配方」1 份 / 8 份
                    com.haiman233.worldtaste.guide.RecipeFillMenu.refill(player, machine, bm,
                            action.isShiftClicked() ? 8 : 1);
                    return false;
                }
                // 左键：打开配方选择界面
                com.haiman233.worldtaste.guide.RecipeFillMenu.open(player, machine, bm);
                return false;
            });
        }
        // 进度占位（有进度槽的电力机器；纯工作台跳过）
        if (progressSlot >= 0 && !placed.contains(progressSlot)) {
            preset.addItem(progressSlot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
        // 输出槽：允许取出（左键提起 / shift 批量取出）、禁止放入。
        // 必须用 AdvancedMenuClickHandler：普通版第三参是槽内物品，左键提起会被误判为放入。
        for (int i : outputSlots) {
            preset.addMenuClickHandler(i, new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, org.bukkit.entity.Player player,
                                       int slot, org.bukkit.inventory.ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }

                @Override
                public boolean onClick(org.bukkit.entity.Player player, int slot, org.bukkit.inventory.ItemStack cursor,
                                       me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }
            });
        }
    }

    /** 配方补全按钮槽位挑选：优先右下角落位顺序，全满时覆盖一个纯装饰边缘槽。 */
    private static int pickFillSlot(Set<Integer> functional, Set<Integer> placed, Set<Integer> extra) {
        int fillSlot = -1;
        for (int s : new int[]{53, 52, 8, 17, 26, 35, 44, 7, 16, 25, 43, 51, 0}) {
            if (!functional.contains(s) && !placed.contains(s) && !extra.contains(s)) { fillSlot = s; break; }
        }
        if (fillSlot < 0) {
            for (int s = 0; s < 54; s++) {
                if (!functional.contains(s) && !placed.contains(s) && !extra.contains(s)) { fillSlot = s; break; }
            }
        }
        if (fillSlot < 0) {
            // GUI 全被功能/装饰占满（如恒温陈酿皿 54 格全满）：覆盖一个边缘装饰槽（仅装饰，不影响功能）
            for (int s : new int[]{53, 52, 8, 17, 26, 35, 44, 43, 51, 0}) {
                if (placed.contains(s) && !functional.contains(s) && !extra.contains(s)) { fillSlot = s; break; }
            }
        }
        return fillSlot;
    }

    /** 配方补全按钮（知识之书）：右键/Shift+右键补全上次配方，左键打开配方选择界面。 */
    private static ItemStack fillButton() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.KNOWLEDGE_BOOK);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GREEN + Lang.get("ui.machinemenubuilder.001"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GREEN + Lang.get("ui.machinemenubuilder.002"));
            lore.add(org.bukkit.ChatColor.GREEN + Lang.get("ui.machinemenubuilder.003"));
            lore.add("");
            lore.add(org.bukkit.ChatColor.GRAY + Lang.get("ui.machinemenubuilder.004"));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
