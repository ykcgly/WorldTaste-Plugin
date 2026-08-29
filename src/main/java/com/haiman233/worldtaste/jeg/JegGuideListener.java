package com.haiman233.worldtaste.jeg;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.guide.BigRecipeMenu;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * JEG 指南事件拦截：
 * <ul>
 *   <li>点击尘世百味机器物品 → 取消 JEG 默认配方页，改开 {@link BigRecipeMenu} 完整配方表；</li>
 *   <li>点击装饰分隔板（{@link DecorativeSubGroup}，普通原版玻璃板占位）→ 取消打开动作，
 *       JEG 的 EventBuilder.ifSuccess 将已取消事件视为「点击已处理」，菜单纹丝不动。</li>
 * </ul>
 * 仅当 JEG 存在时由插件加载本类（见 {@link JegGuideListener#register()}）。
 */
public final class JegGuideListener implements Listener {

    /** 仅在 JEG 可用时调用（类加载安全：本类直接引用 JEG API）。 */
    public static void register() {
        if (JegHook.available()) {
            Bukkit.getPluginManager().registerEvents(new JegGuideListener(), WT.plugin);
            WT.plugin.getLogger().info("JEG 集成：大配方菜单与装饰板点击拦截已启用");
        }
    }

    private JegGuideListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
        // 作弊模式点击 = 领取物品，不拦截（否则终焉厨锅等大型配方机器无法从指南拿取）
        if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
        ItemStack clicked = e.getClickedItem();
        if (clicked == null || clicked.getType().isAir()) return;
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (sf instanceof WTRecipeMachine machine && BigRecipeMenu.isLargeRecipeMachine(machine)) {
            // 仅大型配方机器（如终焉厨锅）拦截并打开大配方菜单；普通机器保留 JEG 默认展示
            e.setCancelled(true);
            BigRecipeMenu.open(e.getPlayer(), machine, 0, null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGroupButtonClick(GuideEvents.ItemGroupButtonClickEvent e) {
        ItemStack clicked = e.getClickedItem();
        if (clicked == null || clicked.getType().isAir()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(DecorativeSubGroup.markKey(), PersistentDataType.BYTE)) {
            // 装饰分隔板：取消打开动作——JEG 视为「点击已处理」，菜单保持原样
            e.setCancelled(true);
        }
    }
}