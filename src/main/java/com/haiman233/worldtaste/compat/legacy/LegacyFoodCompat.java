package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.FoodCompat;
import com.haiman233.worldtaste.WT;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;

/**
 * legacy（1.20.x ~ 1.21.1）食物组件实现：Paper DataComponent API 不存在，全部走反射。
 *
 * <p>该版本段没有 consumable 组件（1.21.2 才引入），FoodComponent 本身即可食——
 * 因此 {@link #hasConsumable} 恒返回 true（懒升级在 legacy 下天然无需进行），
 * {@link #applyConsumable} 为带防护的静默操作（写不进去也不影响 FoodComponent 可食性）。</p>
 */
public final class LegacyFoodCompat implements FoodCompat {

    public static final LegacyFoodCompat INSTANCE = new LegacyFoodCompat();

    private LegacyFoodCompat() {}

    private boolean probed;
    private boolean api;                 // Paper DataComponent API 是否存在（1.21.2+ 才有；legacy 恒 false）
    private Object consumableType;       // DataComponentTypes.CONSUMABLE
    private Method consumableFactory;    // Consumable.consumable()
    private Method bSeconds, bAnimation, bSound;
    private Class<?> animationCls;
    private Method setData, hasData, keyOf;

    private void probe() {
        if (probed) return;
        probed = true;
        try {
            Class<?> dct = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            consumableType = dct.getField("CONSUMABLE").get(null);
            Class<?> consumableCls = Class.forName("io.papermc.paper.datacomponent.item.Consumable");
            consumableFactory = consumableCls.getMethod("consumable");
            Class<?> builderCls = consumableFactory.getReturnType();
            bSeconds = builderCls.getMethod("consumeSeconds", float.class);
            animationCls = Class.forName("io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation");
            bAnimation = builderCls.getMethod("animation", animationCls);
            bSound = builderCls.getMethod("sound", Class.forName("net.kyori.adventure.key.Key"));
            Class<?> valuedCls = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
            Class<?> builderIface = Class.forName("io.papermc.paper.datacomponent.DataComponentBuilder");
            Class<?> typeCls = Class.forName("io.papermc.paper.datacomponent.DataComponentType");
            for (Method m : ItemStack.class.getMethods()) {
                if (m.getName().equals("setData") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == valuedCls
                        && m.getParameterTypes()[1] == builderIface) {
                    setData = m;
                    break;
                }
            }
            for (Method m : ItemStack.class.getMethods()) {
                if (m.getName().equals("hasData") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == typeCls) {
                    hasData = m;
                    break;
                }
            }
            keyOf = Class.forName("net.kyori.adventure.key.Key").getMethod("key", String.class, String.class);
            api = setData != null;
        } catch (Throwable t) {
            api = false;
        }
    }

    @Override
    public boolean hasConsumable(ItemStack stack) {
        probe();
        if (!api || hasData == null) return true; // 无 consumable 概念的版本段：视为已满足
        try {
            return Boolean.TRUE.equals(hasData.invoke(stack, consumableType));
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        probe();
        if (!api) return; // 1.21.2 以下路由不会进入；防御性静默
        try {
            Object builder = consumableFactory.invoke(null);
            if (consumeSeconds > 0) bSeconds.invoke(builder, consumeSeconds);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object anim = Enum.valueOf((Class<? extends Enum>) animationCls, animation.toUpperCase(Locale.ROOT));
            bAnimation.invoke(builder, anim);
            if (soundKey != null && !soundKey.isEmpty()) {
                bSound.invoke(builder, keyOf.invoke(null, "minecraft", soundKey));
            }
            setData.invoke(stack, consumableType, builder);
        } catch (Throwable t) {
            WT.log("Consumable 组件应用失败(legacy): " + t);
        }
    }
}
