package com.haiman233.worldtaste.compat;

import org.bukkit.inventory.ItemStack;

/**
 * 1.20.4- 版本段的食物实现：无 FoodComponent / consumable 组件 API（1.20.5 才引入），
 * 全部回退插件结算——与旧版「无组件」行为逐字一致（食物营养组件不生效、不可原版进食）。
 */
public final class NoopFoodCompat implements FoodCompat {

    public static final NoopFoodCompat INSTANCE = new NoopFoodCompat();

    private NoopFoodCompat() {}

    @Override
    public boolean hasFoodApi() {
        return false;
    }

    @Override
    public boolean applyFood(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat,
                             float eatSeconds, boolean bumpNutrition) {
        return true; // 无 API：空操作，调用方按 hasFoodApi=false 输出一次性低版本日志
    }

    @Override
    public boolean hasConsumable(ItemStack stack) {
        return true; // 无组件概念：视为已满足（legacy-upgrade 懒升级自然不触发）
    }

    @Override
    public void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        // 空操作
    }
}
