package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * 给食物物品应用 Paper FoodComponent（nutrition/saturation/canAlwaysEat/eatSeconds）。
 * 通过 {@link org.bukkit.inventory.meta.ItemMeta#getFood()} 获取组件（无组件时自动创建空实例），
 * 修改后 {@code setFood} 应用；eatSeconds 通过反射调用（Paper 1.21.6+ 支持）。
 *
 * <p>原版进食模式（food.native-eat）另需 consumable 组件（1.21.2+ 才有；决定动画/音效/时长）。
 * Paper 1.21.2+ 的 Bukkit {@code ItemMeta} 无 consumable 方法，须走 Paper DataComponent API
 * （{@code ItemStack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()…)}），
 * 全部经反射调用（类名只以字符串出现，低版本服务端不会 NoClassDefFoundError）。
 * 1.20.5~1.21.1 仅 FoodComponent：可食性成立（food 组件本身可食），但动画/音效固定为默认。</p>
 */
public final class FoodHelper {

    private FoodHelper() {}

    /**
     * 1.20.5+ 才有 {@code ItemMeta#getFood}/FoodComponent（已核对 1.20.4 javadoc 完全不存在）。
     * 启动时探测一次；低版本整体跳过，避免每个食物在 catch(Throwable) 里逐条刷 NoSuchMethodError。
     */
    private static final boolean HAS_FOOD_API = detectFoodApi();
    private static boolean lowVersionLogged = false;

    private static boolean detectFoodApi() {
        try {
            org.bukkit.inventory.meta.ItemMeta.class.getMethod("getFood");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** 组件 API 是否可用（原版进食模式的最低门槛：1.20.5+）。 */
    public static boolean hasFoodApi() {
        return HAS_FOOD_API;
    }

    // ===== Paper 1.21.2+ consumable 组件（反射特征探测） =====
    private static boolean consumableProbed;
    private static boolean consumableApi;
    private static Object consumableType;        // DataComponentTypes.CONSUMABLE
    private static Method consumableFactory;     // Consumable.consumable()
    private static Method bSeconds, bAnimation, bSound;   // Builder 方法
    private static Class<?> animationCls;        // ItemUseAnimation（枚举）
    private static Method setData;               // ItemStack.setData(Valued, DataComponentBuilder)
    private static Method hasData;               // ItemStack.hasData(DataComponentType)
    private static Method keyOf;                 // Key.key(String namespace, String value)
    /** 已成功写入并读回验证的 consumable 组件计数（加载期统计，启动日志用）。 */
    private static int consumableApplied;

    private static void probeConsumableApi() {
        if (consumableProbed) return;
        consumableProbed = true;
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
            consumableApi = setData != null;
        } catch (Throwable t) {
            consumableApi = false;
        }
        WT.plugin.getLogger().info("FoodHelper: FoodComponent API " + (HAS_FOOD_API ? "可用" : "不可用（<1.20.5）")
                + "，Paper consumable 组件 API " + (consumableApi ? "可用" : "不可用（食物将无法原版进食动画）"));
    }

    /** 已成功写入并读回验证的 consumable 组件数量。 */
    public static int consumableAppliedCount() {
        return consumableApplied;
    }

    /**
     * 原版进食物品注入（items.yml 脚本食物用）：FoodComponent + 1.21.2+ 的 consumable 组件
     * （动画/音效）。
     *
     * <p>nutrition/saturation 直接承载脚本的饥饿/饱和恢复值。注意组件化时代（1.20.5 起）food
     * 组件的 {@code saturation} 字段是<b>直接恢复量</b>（1.20.5 Pre-Release 1 由
     * {@code saturation_modifier} 更名并改义），不是旧版 FoodData.eat 的修饰符，无需换算。
     * 恢复上限受当前饥饿值约束——与旧 {@code setSaturation} 的钳制一致。</p>
     *
     * @param canAlwaysEat 饱食度满时是否仍可吃（脚本未要求饥饿 → true，对齐旧 eat() 语义）
     * @param animation    "eat" 或 "drink"
     * @param soundKey     音效资源键（null=组件默认音效）
     * @param eatSeconds   食用时长（&gt;0 时设置，否则默认 1.6s）
     * @param nutrition    组件营养值（0 = 由 consume 事件恢复）
     * @param saturation   组件饱和恢复量（直接值，非修饰符）
     */
    public static void applyNativeFood(ItemStack stack, boolean canAlwaysEat, String animation,
                                       String soundKey, float eatSeconds,
                                       int nutrition, float saturation) {
        apply(stack, nutrition, saturation, canAlwaysEat, eatSeconds, false);
        applyConsumable(stack, eatSeconds, animation, soundKey);
    }

    /**
     * 注入 consumable 组件（动画/音效/食用时长）。仅 1.21.2+ 生效；更低版本静默跳过
     * （FoodComponent 已保证可食，动画/音效为服务端默认）。
     *
     * @param animation    "eat" 或 "drink"（饮品走原版饮用动作与音效）
     * @param soundKey     音效资源键（null=不覆盖，用组件默认音效）
     */
    public static void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        if (stack == null) return;
        probeConsumableApi();
        if (!consumableApi) return;
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
            // 读回验证：setData 静默失败会导致「物品有 food 组件却无法原版进食」的难排查症状
            if (hasData != null && !Boolean.TRUE.equals(hasData.invoke(stack, consumableType))) {
                WT.log("Consumable 组件写入验证失败（setData 已调用但读回为空）");
            } else {
                consumableApplied++;
            }
        } catch (Throwable t) {
            WT.log("Consumable 组件应用失败: " + t);
        }
    }

    /**
     * 应用 FoodComponent。返回是否成功（应用失败时返回 false，便于上层统计升级告警）。
     *
     * <p>对齐 RSC {@code FoodReader} 的取值语义：
     * <ul>
     *   <li>{@code nutrition<1} → 提升为 1（WorldTaste 多数饮品/汁 gz1/gz2/fmjpgz 等 {@code kind:eat} 脚本
     *       在 foods.yml 无 nutrition，缺省 0；RSC 将 &lt;1 一律提升为 1 使其可食，恢复值主体由 onEat
     *       脚本 {@code opts.food/saturation} 提供）；</li>
     *   <li>{@code saturation<0} → 0；</li>
     *   <li>{@code canAlwaysEat} 取食物自身 {@code always_eatable}（默认 false，即饥饿时才可食）。</li>
     * </ul>
     * 实现说明：Paper 1.21.11 起 {@code org.bukkit.craftbukkit.inventory.components.CraftFoodComponent}
     * 已无无参构造器（仅有 FoodProperties / 拷贝 / Map 三种构造），反射 {@code newInstance()} 会抛
     * NoSuchMethodException；改用官方 API {@code ItemMeta#getFood()}：无 food 组件时返回新建的空实例，
     * 返回值为快照，修改后必须 {@code setFood} 应用。兼容 Paper 1.21.2+。
     * {@code eatSeconds} 非标准 API，仍以反射方式尝试，不存在则忽略。</p>
     *
     * @param bumpNutrition false 时不做 {@code <1→1} 提升（原版进食模式下的脚本食物：
     *                      营养值归 0，恢复值全部由 consume 事件的脚本 opts 提供，
     *                      避免「组件数值 + 脚本数值」双重恢复）
     */
    public static boolean apply(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat, float eatSeconds) {
        return apply(stack, nutrition, saturation, canAlwaysEat, eatSeconds, true);
    }

    public static boolean apply(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat,
                                float eatSeconds, boolean bumpNutrition) {
        if (stack == null) return true;
        if (!HAS_FOOD_API) {
            if (!lowVersionLogged) {
                lowVersionLogged = true;
                WT.log("当前服务端低于 1.20.5，无 FoodComponent API，foods.yml 的营养/饱食度组件不生效（脚本食物效果不受影响）");
            }
            return true;
        }
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
                if (eatSeconds > 0) {
                    try {
                        Method m = fc.getClass().getMethod("setEatSeconds", float.class);
                        m.invoke(fc, eatSeconds);
                    } catch (NoSuchMethodException ignored) {
                        // 当前 Paper 版本 FoodComponent 无 eatSeconds，忽略（与 RSC 一致）
                    }
                }
                meta.setFood(fc);
            } catch (Throwable e) {
                ok[0] = false;
                WT.log("FoodComponent 应用失败: " + e);
            }
        });
        return ok[0];
    }
}