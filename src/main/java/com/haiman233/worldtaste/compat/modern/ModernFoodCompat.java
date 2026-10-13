package com.haiman233.worldtaste.compat.modern;

import com.haiman233.worldtaste.compat.FoodCompat;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

/**
 * modern（1.21.2+，主体）食物组件实现：直接调用 Paper DataComponent API，无反射。
 * 仅在 modern 服务端被路由加载——对 1.21.2+ API 的直接引用不会在低版本触发
 * {@link NoClassDefFoundError}。
 */
public final class ModernFoodCompat implements FoodCompat {

    public static final ModernFoodCompat INSTANCE = new ModernFoodCompat();

    private ModernFoodCompat() {}

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
