package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * 给食物物品应用 Paper FoodComponent（nutrition/saturation/canAlwaysEat/eatSeconds）。
 * 通过 {@link org.bukkit.inventory.meta.ItemMeta#getFood()} 获取组件（无组件时自动创建空实例），
 * 修改后 {@code setFood} 应用；eatSeconds 通过反射调用（Paper 1.21.6+ 支持）。
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
     */
    public static boolean apply(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat, float eatSeconds) {
        if (stack == null) return true;
        if (!HAS_FOOD_API) {
            if (!lowVersionLogged) {
                lowVersionLogged = true;
                WT.log("当前服务端低于 1.20.5，无 FoodComponent API，foods.yml 的营养/饱食度组件不生效（脚本食物效果不受影响）");
            }
            return true;
        }
        if (nutrition < 1) nutrition = 1;
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