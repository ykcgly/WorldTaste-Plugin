package com.haiman233.worldtaste.compat;

/**
 * 版本兼容路由：按服务端能力把调用派发到 {@code legacy} / {@code modern} 子包实现。
 *
 * <p>判定标准 = Paper DataComponent API（{@code io.papermc.paper.datacomponent.item.Consumable}，
 * MC 1.21.2+）是否存在：</p>
 * <ul>
 *   <li><b>modern</b>（1.21.2+，主体）：直接调用 Paper API，无反射；</li>
 *   <li><b>legacy</b>（1.20.x ~ 1.21.1）：反射实现（与 modern 同接口、不同内部形态）。</li>
 * </ul>
 *
 * <p>类加载安全：modern 实现类只在路由命中 modern 分支时才会被加载——其内部对 1.21.2+
 * 新 API 的直接引用不会在 1.20.x 运行时触发 {@link NoClassDefFoundError}。
 * 判定与路由一次完成（静态初始化），运行期零开销。</p>
 */
public final class Compat {

    private static final boolean MODERN = detectModern();
    private static final FoodCompat FOOD = MODERN
            ? com.haiman233.worldtaste.compat.modern.ModernFoodCompat.INSTANCE
            : com.haiman233.worldtaste.compat.legacy.LegacyFoodCompat.INSTANCE;
    private static final ViewCompat VIEW = MODERN
            ? com.haiman233.worldtaste.compat.modern.ModernViewCompat.INSTANCE
            : com.haiman233.worldtaste.compat.legacy.LegacyViewCompat.INSTANCE;

    private Compat() {}

    private static boolean detectModern() {
        try {
            Class.forName("io.papermc.paper.datacomponent.item.Consumable");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否为 modern 服务端（1.21.2+，具备 Paper DataComponent API）。 */
    public static boolean isModern() {
        return MODERN;
    }

    /** 食物组件兼容层（FoodComponent / consumable 组件）。 */
    public static FoodCompat food() {
        return FOOD;
    }

    /** InventoryView 兼容层（1.21 起由抽象类改为接口）。 */
    public static ViewCompat views() {
        return VIEW;
    }
}
