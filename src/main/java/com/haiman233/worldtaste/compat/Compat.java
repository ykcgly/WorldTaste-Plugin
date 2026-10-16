package com.haiman233.worldtaste.compat;

/**
 * 版本兼容路由：按服务器版本把调用派发到 {@code legacy} / {@code modern} / noop 实现。
 *
 * <p><b>结构（1.9.9 起，Gradle 多源集）</b>：版本形态不同的 API 不再反射——各实现类放在
 * 与其运行形态匹配的 Gradle source set 中、用对应版本 paper-api 编译期直调：</p>
 * <ul>
 *   <li><b>main</b>（Paper 1.21.11 基线）：compat 接口 + noop 实现（1.20.4- / &lt;1.20.2）；</li>
 *   <li><b>src/legacy/java</b>（Paper 1.20.6 编译）：1.20.x 运行形态——InventoryView 抽象类、
 *       FoodComponent（1.20.5+）、Sound 枚举、getBasePotionType；</li>
 *   <li><b>src/modern/java</b>（Paper 1.21.11 编译）：1.21.x 运行形态——DataComponent
 *       consumable 组件（1.21.2+）、InventoryView 接口、Sound 接口（Registry）。</li>
 * </ul>
 *
 * <p><b>版本判定（无反射）</b>：{@code Bukkit.getMinecraftVersion()} 字符串解析；
 * 解析失败回退 modern 主基线（1.21.11）。</p>
 *
 * <p><b>类加载安全</b>：实现类经 main 内的静态 Holder 惰性加载——Compat 类的验证期只会
 * 触碰 Holder（main 类，无高版本 API 引用），实现类仅在路由命中对应版本段时才会被
 * 加载/验证，反向版本段运行时不会出现 {@link NoClassDefFoundError} 或
 * {@link IncompatibleClassChangeError}。路由一次完成（静态初始化），运行期零开销。</p>
 *
 * <p>路由边界与依据：</p>
 * <ul>
 *   <li>food：≥1.21.2 → modern（DataComponent consumable）；≥1.20.5 → legacy（FoodComponent）；
 *       否则 noop（无组件 API，回退插件结算）；</li>
 *   <li>view：≥1.21 → modern（InventoryView 接口）；否则 legacy（抽象类）；</li>
 *   <li>potion：≥1.20.2 → legacy 源集实现（getBasePotionType，接口方法两侧形态一致）；
 *       否则 noop；</li>
 *   <li>sound：≥1.21.3 → modern（Registry）；否则 legacy（枚举 valueOf）。</li>
 * </ul>
 */
public final class Compat {

    private Compat() {}

    /** 解析后的服务端版本 [major, minor, patch]；解析失败回退 modern 主基线 1.21.11。 */
    private static final int[] VERSION = parseVersion();

    private static final FoodCompat FOOD = createFood();
    private static final ViewCompat VIEW = createView();
    private static final PotionCompat POTION = createPotion();
    private static final SoundCompat SOUND = createSound();

    public static FoodCompat food() {
        return FOOD;
    }

    public static ViewCompat views() {
        return VIEW;
    }

    public static PotionCompat potions() {
        return POTION;
    }

    public static SoundCompat sounds() {
        return SOUND;
    }

    // ---- 版本判定（无反射：Bukkit.getMinecraftVersion() 字符串解析） ----

    private static int[] parseVersion() {
        try {
            String v = org.bukkit.Bukkit.getMinecraftVersion();
            String[] parts = v.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (Throwable t) {
            return new int[]{1, 21, 11};
        }
    }

    private static boolean atLeast(int major, int minor, int patch) {
        int[] v = VERSION;
        if (v[0] != major) return v[0] > major;
        if (v[1] != minor) return v[1] > minor;
        return v[2] >= patch;
    }

    // ---- 路由（实现类经 Holder 惰性加载，见类注释） ----

    private static FoodCompat createFood() {
        if (atLeast(1, 21, 2)) return ModernFoodHolder.I;
        if (atLeast(1, 20, 5)) return LegacyFoodHolder.I;
        return NoopFoodCompat.INSTANCE;
    }

    private static ViewCompat createView() {
        if (atLeast(1, 21, 0)) return ModernViewHolder.I;
        return LegacyViewHolder.I;
    }

    private static PotionCompat createPotion() {
        if (atLeast(1, 20, 2)) return LegacyPotionHolder.I;
        return NoopPotionCompat.INSTANCE;
    }

    private static SoundCompat createSound() {
        if (atLeast(1, 21, 3)) return ModernSoundHolder.I;
        return LegacySoundHolder.I;
    }

    /**
     * 静态 Holder：实现类的加载推迟到对应路由分支首次执行时。
     * Holder 本身仅引用 main 内类型，Compat 的类验证不会解析高/低版本 API。
     */
    private static final class ModernFoodHolder {
        static final FoodCompat I = com.haiman233.worldtaste.compat.modern.ModernFoodCompat.INSTANCE;
    }

    private static final class LegacyFoodHolder {
        static final FoodCompat I = com.haiman233.worldtaste.compat.legacy.LegacyFoodCompat.INSTANCE;
    }

    private static final class ModernViewHolder {
        static final ViewCompat I = com.haiman233.worldtaste.compat.modern.ModernViewCompat.INSTANCE;
    }

    private static final class LegacyViewHolder {
        static final ViewCompat I = com.haiman233.worldtaste.compat.legacy.LegacyViewCompat.INSTANCE;
    }

    private static final class LegacyPotionHolder {
        static final PotionCompat I = com.haiman233.worldtaste.compat.legacy.LegacyPotionCompat.INSTANCE;
    }

    private static final class ModernSoundHolder {
        static final SoundCompat I = com.haiman233.worldtaste.compat.modern.ModernSoundCompat.INSTANCE;
    }

    private static final class LegacySoundHolder {
        static final SoundCompat I = com.haiman233.worldtaste.compat.legacy.LegacySoundCompat.INSTANCE;
    }
}
