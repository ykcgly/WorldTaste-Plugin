package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.FoodCompat;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * legacy（1.20.5 ~ 1.21.1）食物实现：Paper 1.20.6 编译、Bukkit FoodComponent API 直调，
 * 全程无反射。该版本段没有 consumable 组件（1.21.2 才引入），FoodComponent 本身即可食——
 * {@link #hasConsumable} 恒 true（懒升级无需进行）、{@link #applyConsumable} 为空操作。
 *
 * <p>运行区间 1.20.5 ~ 1.21.1：{@code ItemMeta#getFood/setFood/setEatSeconds} 在 1.20.5+
 * 存在且签名稳定；1.20.4- 由 Compat 路由到 NoopFoodCompat，不会加载本类。</p>
 */
public final class LegacyFoodCompat implements FoodCompat {

    public static final LegacyFoodCompat INSTANCE = new LegacyFoodCompat();

    private LegacyFoodCompat() {}

    @Override
    public boolean hasFoodApi() {
        return true; // 1.20.5+ 恒有 ItemMeta#getFood
    }

    @Override
    public boolean applyFood(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat,
                             float eatSeconds, boolean bumpNutrition) {
        if (stack == null) return true;
        if (bumpNutrition && nutrition < 1) nutrition = 1;
        if (saturation < 0f) saturation = 0f;
        final int fFood = nutrition;
        final float fSat = saturation;
        final boolean fAlways = canAlwaysEat;
        boolean[] ok = {true};
        stack.editMeta(meta -> {
            try {
                FoodComponent fc = meta.getFood();
                fc.setNutrition(fFood);
                fc.setSaturation(fSat);
                fc.setCanAlwaysEat(fAlways);
                // eatSeconds 不写入：Bukkit FoodComponent 接口无 setEatSeconds（原实现为此
                // 反射碰运气，现按「不许用反射」约束放弃），本版本段食用时长为原版默认 1.6s
                meta.setFood(fc);
            } catch (Throwable e) {
                // 日志由 main 侧调用方（FoodHelper）按 false 返回值输出——legacy 源集不依赖 main
                ok[0] = false;
            }
        });
        return ok[0];
    }

    @Override
    public boolean hasConsumable(ItemStack stack) {
        return true; // <1.21.2 无 consumable 组件：FoodComponent 本身即可食
    }

    @Override
    public void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        // 空操作：<1.21.2 无 consumable 组件，FoodComponent 可食性已足够
    }
}
