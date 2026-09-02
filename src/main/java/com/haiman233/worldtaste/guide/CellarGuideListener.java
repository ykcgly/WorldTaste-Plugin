package com.haiman233.worldtaste.guide;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.jeg.JegHook;
import com.haiman233.worldtaste.machines.CellarRecipeMenu;
import com.haiman233.worldtaste.machines.WineCellarManager;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 指南酒窖管理器入口（双模式，对齐榨汁盆）：
 * <ul>
 *   <li><b>JEG 模式</b>（安装 JEG 时启用 {@link CellarJegEntryListener}）：拦截指南点击
 *       酒窖管理器，打开 {@link CellarRecipeMenu#openOverview} 自身配方概览页；</li>
 *   <li><b>注入模式</b>（无 JEG 的原版 Slimefun 指南）：点击放行默认配方页，延迟注入
 *       书本按钮到右下角。</li>
 * </ul>
 * 点击带标记的书本按钮 → 打开 {@link CellarRecipeMenu} 配方展示页。
 */
public final class CellarGuideListener implements Listener {

    public static final NamespacedKey KEY_ENTRY =
            new NamespacedKey(WT.plugin, "jiujiao_menu_entry");

    public static void register() {
        Bukkit.getPluginManager().registerEvents(new CellarGuideListener(), WT.plugin);
        if (JegHook.available()) {
            Bukkit.getPluginManager().registerEvents(new CellarJegEntryListener(), WT.plugin);
            WT.plugin.getLogger().info("酒窖指南入口：JEG 拦截模式");
        } else {
            WT.plugin.getLogger().info("酒窖指南入口：原版注入模式");
        }
    }

    private CellarGuideListener() {}

    /** 无 JEG：指南中点击酒窖管理器 → 放行默认展示，延迟注入入口按钮。 */
    @EventHandler
    public void onManagerClick(InventoryClickEvent e) {
        if (JegHook.available()) return; // JEG 模式由 CellarJegEntryListener 处理
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (!(sf instanceof WineCellarManager)) return;
        Player p = (Player) e.getWhoClicked();
        Bukkit.getScheduler().runTaskLater(WT.plugin, () -> injectEntry(p), 2);
    }

    /** 在酒窖管理器配方页右下角注入书本入口（已有的 slot 依次向前寻找空位）。 */
    private void injectEntry(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getSize() < 54) return;
        boolean isManagerPage = false;
        for (ItemStack it : top) {
            if (it == null) continue;
            SlimefunItem sf = SlimefunItem.getByItem(it);
            if (sf instanceof WineCellarManager) {
                isManagerPage = true;
                break;
            }
        }
        if (!isManagerPage) return;
        for (int slot = 53; slot >= 45; slot--) {
            ItemStack cur = top.getItem(slot);
            if (cur != null && isEntry(cur)) return; // 已注入
            // 原版指南配方页空位也会填背景板：背景板视为可注入位置
            if (cur == null || cur.getType().isAir() || cur == ChestMenuUtils.getBackground()) {
                top.setItem(slot, CellarRecipeMenu.entryButton());
                return;
            }
        }
    }

    /** 点击入口按钮：打开展示菜单。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntryClick(InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || !isEntry(cur)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p) {
            CellarRecipeMenu.openRecipes(p, 0, null);
        }
    }

    private static boolean isEntry(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(KEY_ENTRY, PersistentDataType.BYTE);
    }

    /** JEG 模式：拦截指南点击酒窖管理器，打开自身配方概览页。 */
    public static final class CellarJegEntryListener implements Listener {

        public CellarJegEntryListener() {}

        @EventHandler(ignoreCancelled = true)
        public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
            // 作弊模式点击 = 领取物品，不拦截
            if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
            ItemStack clicked = e.getClickedItem();
            if (clicked == null) return;
            SlimefunItem sf = SlimefunItem.getByItem(clicked);
            if (!(sf instanceof WineCellarManager)) return;
            e.setCancelled(true);
            CellarRecipeMenu.openOverview(e.getPlayer());
        }
    }
}
