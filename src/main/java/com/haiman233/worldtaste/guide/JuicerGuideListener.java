package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerBasin;
import com.haiman233.worldtaste.jeg.JegHook;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * 指南榨汁盆入口（双模式）：
 * <ul>
 *   <li><b>JEG 模式</b>（安装 JEG 时启用 {@link JuicerJegEntryListener}）：拦截指南点击榨汁盆，
 *       打开 {@link JuicerMenu#openBasinOverview} 自身配方概览页，右下角金苹果进入配方展示页
 *       （JEG 指南页面经其 formatter 重绘且大于 54 格，无法按物品扫描注入）；</li>
 *   <li><b>注入模式</b>（无 JEG 的原版 Slimefun 指南）：点击榨汁盆放行默认配方页，延迟注入
 *       金苹果按钮到右下角。</li>
 * </ul>
 * 点击带标记的金苹果按钮 → 打开 {@link JuicerMenu} 配方展示页。
 */
public final class JuicerGuideListener implements Listener {

    static final NamespacedKey KEY_ENTRY =
            new NamespacedKey(WT.plugin, "zhapen_menu_entry");

    public static void register() {
        Bukkit.getPluginManager().registerEvents(new JuicerGuideListener(), WT.plugin);
        if (JegHook.available()) {
            Bukkit.getPluginManager().registerEvents(new JuicerJegEntryListener(), WT.plugin);
            WT.plugin.getLogger().info("榨汁盆指南入口：JEG 拦截模式");
        } else {
            WT.plugin.getLogger().info("榨汁盆指南入口：原版注入模式");
        }
    }

    private JuicerGuideListener() {}

    /** 无 JEG：指南中点击榨汁盆 → 放行默认展示，延迟注入入口按钮。 */
    @EventHandler
    public void onBasinClick(InventoryClickEvent e) {
        if (JegHook.available()) return; // JEG 模式由 JuicerJegEntryListener 处理
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (!(sf instanceof JuicerBasin)) return;
        Player p = (Player) e.getWhoClicked();
        Bukkit.getScheduler().runTaskLater(WT.plugin, () -> injectEntry(p), 2);
    }

    /** 在榨汁盆配方页右下角注入金苹果入口（已有的 slot 依次向前寻找空位）。 */
    private void injectEntry(Player p) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getSize() < 54) return;
        boolean isBasinPage = false;
        for (ItemStack it : top) {
            if (it == null) continue;
            SlimefunItem sf = SlimefunItem.getByItem(it);
            if (sf instanceof JuicerBasin) {
                isBasinPage = true;
                break;
            }
        }
        if (!isBasinPage) return;
        for (int slot = 53; slot >= 45; slot--) {
            ItemStack cur = top.getItem(slot);
            if (cur != null && isEntry(cur)) return; // 已注入
            // 原版指南配方页空位也会填背景板：背景板视为可注入位置
            if (cur == null || cur.getType().isAir() || cur == ChestMenuUtils.getBackground()) {
                top.setItem(slot, JuicerMenu.entryButton());
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
            JuicerMenu.openRecipes(p, 0);
        }
    }

    private static boolean isEntry(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(KEY_ENTRY, PersistentDataType.BYTE);
    }

    private static ItemStack entryButton() {
        ItemStack it = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "榨汁盆配方");
            meta.getPersistentDataContainer().set(KEY_ENTRY, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }
}
