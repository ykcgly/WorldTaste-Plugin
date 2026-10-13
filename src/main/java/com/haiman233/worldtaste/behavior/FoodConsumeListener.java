package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.items.ConsumableItem;
import com.haiman233.worldtaste.load.WTConfig;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/**
 * 食物进食事件：对 foods.yml 带 onEat 脚本(gz/rou 等)的食物，在进食后追加饥饿/饱和/消耗/药水等效果。
 * （食物的基础 nutrition 由 FoodComponent 提供，此处仅追加脚本效果，对齐原 WT_eatFood。）
 */
public final class FoodConsumeListener implements Listener {

    public static final FoodConsumeListener INSTANCE = new FoodConsumeListener();

    private FoodConsumeListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        SlimefunItem sf = SlimefunItem.getByItem(e.getItem());
        if (sf == null) return;
        if (sf instanceof ConsumableItem ci) {
            // 原版进食模式（food.native-eat）：食物类（非 offhandTool 工具）的进食就是原版管线——
            // 动画/咀嚼音/时长/物品消耗全由原版完成，这里只补脚本效果（饥饿/饱和/药水/提示/联动）
            if (WTConfig.nativeEat && com.haiman233.worldtaste.load.FoodHelper.hasFoodApi()
                    && ci.opts().offhandTool == null) {
                if (WTConfig.debugFood) {
                    WT.log("[debug-food] " + sf.getId() + " (" + e.getPlayer().getName()
                            + ") 原版进食完成 → 应用脚本效果"
                            + (ci.opts().componentCarriesFood() ? "（饥饿/饱和已由组件恢复）" : ""));
                }
                // 简单数值型食物的饥饿/饱和已由 FoodComponent 在原版进食时恢复，跳过防双重结算
                ConsumableItem.applyConsumeEffects(e.getPlayer(), ci.opts(), sf.getId(),
                        ci.opts().componentCarriesFood());
                return;
            }
            // 旧模式：脚本类食物（ConsumableItem）绝不允许被原版进食链路消耗：食用一律由脚本
            // 统一处理（饱食度校验/副手校验/药水/提示/音效），原版链路会绕过这一切——实测服上
            // 「满饱食度右键仍被消耗」即来自这条路径，这里直接拦截，物品不消耗。
            e.setCancelled(true);
            if (WTConfig.debugFood) {
                WT.log("[debug-food] " + sf.getId() + " 脚本类食物的原版进食已拦截（不允许原版消耗）");
            }
            return;
        }
        // 诊断（debug.food 开启时）：物品走的是「原版进食」路径而不是脚本食用
        if (WTConfig.debugFood) {
            WT.log("[debug-food] " + sf.getId() + " (" + e.getPlayer().getName()
                    + ") 走原版进食路径（PlayerItemConsumeEvent）");
        }
        ConsumableOpts opts = Behaviors.foodOnEat.get(sf.getId());
        if (opts == null) return;
        Player p = e.getPlayer();
        if (opts.food != null) p.setFoodLevel(p.getFoodLevel() + opts.food.intValue());
        if (opts.saturation != null) p.setSaturation((float) (p.getSaturation() + opts.saturation));
        if (opts.exhaustion != null) p.setExhaustion((float) (p.getExhaustion() - opts.exhaustion));
        for (Behaviors.Potion pt : opts.potions) {
            org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(pt.type);
            if (type != null) p.addPotionEffect(new org.bukkit.potion.PotionEffect(type, pt.duration, pt.amplifier, false));
        }
    }
}
