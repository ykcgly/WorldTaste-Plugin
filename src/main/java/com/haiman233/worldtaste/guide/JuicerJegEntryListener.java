package com.haiman233.worldtaste.guide;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.machines.JuicerBasin;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * JEG 模式榨汁盆入口（仅 JEG 可用时注册，本类直接引用 JEG API）：
 * 拦截指南中点击榨汁盆，打开自身配方概览页（右下角金苹果进入配方展示页）。
 */
public final class JuicerJegEntryListener implements Listener {

    public JuicerJegEntryListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
        // 作弊模式点击 = 领取物品，不拦截
        if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
        ItemStack clicked = e.getClickedItem();
        if (clicked == null) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (!(sf instanceof JuicerBasin)) return;
        e.setCancelled(true);
        JuicerMenu.openBasinOverview(e.getPlayer());
    }
}
