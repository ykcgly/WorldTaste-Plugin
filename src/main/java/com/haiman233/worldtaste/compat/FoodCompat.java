package com.haiman233.worldtaste.compat;

import org.bukkit.inventory.ItemStack;

/**
 * 食物 consumable 组件兼容层（供原版进食模式注入动画/音效/时长）。
 * 实现见 {@code compat.legacy.LegacyFoodCompat}（反射）与
 * {@code compat.modern.ModernFoodCompat}（Paper DataComponent API 直调）。
 */
public interface FoodCompat {

    /**
     * 物品是否已带 consumable 组件。
     * legacy（1.21.2 以下无此组件）恒返回 true——该版本段 FoodComponent 本身即可食，无需升级。
     */
    boolean hasConsumable(ItemStack stack);

    /**
     * 注入 consumable 组件（动画/音效/食用时长）。
     *
     * @param animation "eat" 或 "drink"
     * @param soundKey  音效资源键（null = 组件默认音效）
     */
    void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey);
}
