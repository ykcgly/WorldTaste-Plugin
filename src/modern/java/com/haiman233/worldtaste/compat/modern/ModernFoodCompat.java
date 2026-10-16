package com.haiman233.worldtaste.compat.modern;

import com.haiman233.worldtaste.compat.FoodCompat;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

/**
 * modern（1.21.2+，主体）食物实现：Paper 1.21.11 编译，FoodComponent（Bukkit）与
 * consumable 组件（Paper DataComponent API）全部直调，无反射。
 * 仅在 modern 服务端被路由加载——对 1.21.2+ API 的直接引用不会在低版本触发
 * {@link NoClassDefFoundError}（见 Compat 的 Holder 惰性加载）。
 */
public final class ModernFoodCompat implements FoodCompat {

    public static final ModernFoodCompat INSTANCE = new ModernFoodCompat();

    private ModernFoodCompat() {}

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
        try {
            // 用 DataComponent FOOD 组件直写（替代 Bukkit meta.setFood）：Bukkit FoodComponent
            // 接口没有 setEatSeconds（原实现为此反射碰运气），modern 段的食用时长统一由
            // consumable 组件承载（applyConsumable 的 consumeSeconds），此处忽略 eatSeconds
            FoodProperties fp = FoodProperties.food()
                    .nutrition(nutrition)
                    .saturation(saturation)
                    .canAlwaysEat(canAlwaysEat)
                    .build();
            stack.setData(DataComponentTypes.FOOD, fp);
            return true;
        } catch (Throwable e) {
            // 日志由 main 侧调用方（FoodHelper）按 false 返回值输出——modern 源集不依赖 main
            return false;
        }
    }

    @Override
    public boolean hasConsumable(ItemStack stack) {
        return stack != null && stack.hasData(DataComponentTypes.CONSUMABLE);
    }

    @Override
    public void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        Consumable.Builder builder = Consumable.consumable();
        if (consumeSeconds > 0) builder.consumeSeconds(consumeSeconds);
        builder.animation(ItemUseAnimation.valueOf(animation.toUpperCase(Locale.ROOT)));
        if (soundKey != null && !soundKey.isEmpty()) {
            builder.sound(Key.key("minecraft", soundKey));
        }
        stack.setData(DataComponentTypes.CONSUMABLE, builder);
    }
}
