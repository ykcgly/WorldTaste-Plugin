package com.haiman233.worldtaste.jeg;

import com.balugaq.jeg.api.objects.events.GuideEvents;
import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.guide.BigRecipeMenu;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.machines.WTMachine;
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
 *   <li>点击大型配方机器的工作产物 → 直接打开该产物的大配方展示页（免去「点产物→看 9 格
 *       小配方」的弯路）；</li>
 *   <li>点击材料指南页的「返回」（大配方页材料点击跳转后的场景）→ 回到来源大配方页
 *       （「从哪来回哪去」，返回链见 {@link BigRecipeMenu#pushReturn}）；</li>
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
            WT.plugin.getLogger().info("JEG 集成：大配方菜单、产物跳转、返回链拦截已启用");
        }
    }

    private JegGuideListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onItemClick(GuideEvents.ItemButtonClickEvent e) {
        // 作弊模式点击 = 领取物品，不拦截（否则终焉厨锅等大型配方机器无法从指南拿取）
        if (e.getGuide().getMode() != SlimefunGuideMode.SURVIVAL_MODE) return;
        ItemStack clicked = e.getClickedItem();
        if (clicked == null || clicked.getType().isAir()) return;
        // 点击指南中的物品 = 玩家在 JEG 页间导航离开 → 材料页返回链失效
        //（深入浏览后点返回应交还 JEG 自行逐级返回，而非弹回大配方页）
        BigRecipeMenu.invalidateReturn(e.getPlayer());
        SlimefunItem sf = SlimefunItem.getByItem(clicked);
        if (sf instanceof WTMachine machine && BigRecipeMenu.isLargeRecipeMachine(machine)) {
            // 仅大型配方机器（如终焉厨锅）拦截并打开大配方菜单；普通机器保留 JEG 默认展示
            e.setCancelled(true);
            BigRecipeMenu.open(e.getPlayer(), machine, 0, null);
            return;
        }
        // 大型配方机器的工作产物：直接打开该产物的大配方展示页（单个配方直达；多配方开产物列表）。
        // fromList=false：来源是指南而非产物列表——工作配方页返回键应回指南（从哪来回哪去）
        WTRecipeMachine.ProductHit hit = WTRecipeMachine.findByProduct(clicked);
        if (hit != null && BigRecipeMenu.isLargeRecipeMachine(hit.machine())) {
            e.setCancelled(true);
            if (hit.indices().length == 1) {
                BigRecipeMenu.openWorkPage(e.getPlayer(), hit.machine(), hit.indices(), 0, false, null);
            } else {
                BigRecipeMenu.openProductList(e.getPlayer(), hit.machine(), 0, null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBackButtonClick(GuideEvents.BackButtonClickEvent e) {
        // 大配方页 → 材料指南页的返回链（从哪来回哪去）：
        //   玩家已退回到材料页本身（历史栈深度 == 跳出前深度 + 1）→ 拦截，回来源大配方页；
        //   还在材料页更深层的 JEG 页里（深度更大）→ 不干预，JEG 自行逐级返回。
        // 无返回记录（普通指南浏览）时也不干预，走 JEG 默认返回。
        var entry = BigRecipeMenu.peekReturn(e.getPlayer());
        if (entry == null) return;
        java.util.Optional<io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile> profile =
                io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile.find(e.getPlayer());
        if (profile.isEmpty()) return;
        if (profile.get().getGuideHistory().size() > entry.historySize() + 1) return;
        e.setCancelled(true);
        BigRecipeMenu.consumeReturn(e.getPlayer()).reopen().run();
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