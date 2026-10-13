package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.compat.Compat;
import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * 给食物物品应用 FoodComponent / consumable 组件（原版进食模式的数据层）。
 *
 * <p>两层职责：</p>
 * <ul>
 *   <li><b>FoodComponent</b>（1.20.5+，直接 API）：nutrition/saturation/canAlwaysEat，
 *       由 {@link #apply} 应用——1.21.2 起可食性由 consumable 组件决定，本组件只承载恢复数值；</li>
 *   <li><b>consumable 组件</b>（1.21.2+）：动画/音效/食用时长，版本差异经
 *       {@link Compat#food()} 路由到 {@code compat.legacy}（反射）/ {@code compat.modern}
 *       （直调）子包实现，本类不再持有任何反射代码。</li>
 * </ul>
 */
public final class FoodHelper {

    private FoodHelper() {}

    /**
     * 1.20.5+ 才有 {@code ItemMeta#getFood}/FoodComponent（已核对 1.20.4 javadoc 完全不存在）。
     * 启动时探测一次；低版本整体跳过，避免每个食物在 catch(Throwable) 里逐条刷 NoSuchMethodError。
     */
    private static final boolean HAS_FOOD_API = detectFoodApi();
    private static boolean lowVersionLogged = false;
    /** 已成功写入的 consumable 组件计数（Setup.loadAll 末尾汇总输出）。 */
    private static int consumableApplied;

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

    /** 已成功写入的 consumable 组件数量。 */
    public static int consumableAppliedCount() {
        return consumableApplied;
    }

    /** 物品是否已带 consumable 组件（legacy 版本段恒 true——FoodComponent 本身即可食）。 */
    public static boolean hasConsumable(ItemStack stack) {
        return Compat.food().hasConsumable(stack);
    }

    /** 注入 consumable 组件（动画/音效/食用时长；经 compat 路由，legacy 下为静默空操作）。 */
    public static void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey) {
        if (stack == null) return;
        Compat.food().applyConsumable(stack, consumeSeconds, animation, soundKey);
        if (hasConsumable(stack)) {
            consumableApplied++;
        } else {
            WT.log("Consumable 组件写入验证失败（写入后读回为空）");
        }
    }

    /**
     * 原版进食物品注入（items.yml 脚本食物用）：FoodComponent + consumable 组件（动画/音效）。
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
