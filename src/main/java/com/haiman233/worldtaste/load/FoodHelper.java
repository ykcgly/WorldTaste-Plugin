package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.compat.Compat;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import com.haiman233.worldtaste.util.Lang;

/**
 * 给食物物品应用 FoodComponent / consumable 组件（原版进食模式的数据层）。
 *
 * <p>两层职责（均经 {@link Compat#food()} 版本路由，本类无任何反射与版本判定）：</p>
 * <ul>
 *   <li><b>FoodComponent</b>（1.20.5+，Bukkit API）：nutrition/saturation/canAlwaysEat/
 *       eatSeconds，由路由实现直调写入——1.21.2 起可食性由 consumable 组件决定，
 *       本组件只承载恢复数值；</li>
 *   <li><b>consumable 组件</b>（1.21.2+）：动画/音效/食用时长，路由到
 *       {@code compat.modern}（DataComponent 直调）；legacy（1.20.5~1.21.1）与
 *       noop（1.20.4-）版本段为语义化空操作。</li>
 * </ul>
 */
public final class FoodHelper {

    private FoodHelper() {}

    private static boolean lowVersionLogged = false;
    /** 已成功写入的 consumable 组件计数（Setup.loadAll 末尾汇总输出）。 */
    private static int consumableApplied;

    /** FoodComponent API 是否可用（原版进食模式的最低门槛：1.20.5+，由 compat 路由保证）。 */
    public static boolean hasFoodApi() {
        return Compat.food().hasFoodApi();
    }

    /** 已成功写入的 consumable 组件数量。 */
    public static int consumableAppliedCount() {
        return consumableApplied;
    }

    /** 物品是否已带 consumable 组件（legacy/noop 版本段恒 true——FoodComponent 本身即可食）。 */
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
            WT.log(Lang.get("log.foodhelper.001"));
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
     * @param canAlwaysEat 饱食度满时是否仍可吃。尘世百味全部食物恒传 true——进食饱食度
     *                      检测已整体移除（原脚本 requireHungry / foods.yml always_eatable
     *                      均废弃），满饱食度与创造模式同样可食用；参数保留是为兼容
     *                      其它可能的调用方。
     * @param animation    "eat" 或 "drink"
     * @param soundKey     音效资源键（null=组件默认音效）
     * @param eatSeconds   食用时长（&gt;0 时设置，否则默认 1.6s）
     * @param nutrition    组件营养值（0 = 由 consume 事件恢复）
     * @param saturation   组件饱和恢复量（直接值，非修饰符）
     */
    public static void applyNativeFood(ItemStack stack, boolean canAlwaysEat, String animation,
                                       String soundKey, float eatSeconds,
                                       int nutrition, float saturation) {
        applyNativeFood(stack, canAlwaysEat, animation, soundKey, eatSeconds, nutrition, saturation, null);
    }

    /**
     * 同上，另把吃完药水效果一并注入 consumable 组件（1.21.2+ 原版进食自动生效）。
     * legacy/noop 版本段经 compat default 退化为无效果版本——药水仍由脚本在
     * consume 事件结算，调用方按 {@code Compat.hasConsumeEffectsApi()} 设置
     * {@code componentCarriesEffects} 标记防止双重结算。
     */
    public static void applyNativeFood(ItemStack stack, boolean canAlwaysEat, String animation,
                                       String soundKey, float eatSeconds,
                                       int nutrition, float saturation, List<PotionEffect> effects) {
        apply(stack, nutrition, saturation, canAlwaysEat, eatSeconds, false);
        if (effects != null && !effects.isEmpty()) {
            Compat.food().applyConsumableWithEffects(stack, eatSeconds, animation, soundKey, effects);
            if (hasConsumable(stack)) {
                consumableApplied++;
            } else {
                WT.log(Lang.get("log.foodhelper.001"));
            }
        } else {
            applyConsumable(stack, eatSeconds, animation, soundKey);
        }
    }

    // ---- 头颅食物双形态（1.21.4+ item_model 构造） ----

    /** 头颅类材质集合（物品不会是墙面变体，无需收录 *_WALL_HEAD）。 */
    private static final java.util.Set<Material> HEAD_MATERIALS = java.util.EnumSet.of(
            Material.PLAYER_HEAD, Material.SKELETON_SKULL, Material.ZOMBIE_HEAD,
            Material.WITHER_SKELETON_SKULL, Material.CREEPER_HEAD, Material.DRAGON_HEAD,
            Material.PIGLIN_HEAD);

    /** 物品材质是否为头颅类（头颅食物形态改造的判定入口）。 */
    public static boolean isHeadMaterial(Material m) {
        return m != null && HEAD_MATERIALS.contains(m);
    }

    /**
     * 构造「下界砖本体形态」基底：NETHER_BRICK + 复制原头颅物品的名称与 lore。
     * 纯 Bukkit API、版本无关；item_model/profile 等组件由调用方经 compat 注入。
     * 失败返回 null，调用方回退头颅本体构造（1.21.4- 与旧存档继续头颅形态）。
     *
     * <p>本体选 NETHER_BRICK 而非蜂蜜瓶：原版不可堆肥、不可放营火、自带 64 堆叠、
     * 无 use_remainder 残留物，无需任何「取消默认组件」的补丁。</p>
     */
    public static SlimefunItemStack buildBrickForm(String id, ItemStack headDisplay) {
        try {
            ItemStack brick = new ItemStack(Material.NETHER_BRICK);
            ItemMeta src = headDisplay.getItemMeta();
            ItemMeta meta = brick.getItemMeta();
            if (src == null || meta == null) return null;
            if (src.hasDisplayName()) meta.setDisplayName(src.getDisplayName());
            if (src.hasLore()) meta.setLore(src.getLore());
            brick.setItemMeta(meta);
            // PDC sf id 由 SlimefunItemStack 构造器写入——两种形态同一 id，
            // 指南/配方/机器识别全部兼容（RecipeMatch 按 id 兜底匹配）
            return new SlimefunItemStack(id, brick);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 脚本药水列表 → Bukkit PotionEffect（ambient=false，与脚本 {@code addPotionEffect}
     * 结算语义一致）。无法解析的药水类型照旧折叠日志。
     */
    public static List<PotionEffect> potionEffects(List<com.haiman233.worldtaste.behavior.Behaviors.Potion> pts) {
        if (pts == null || pts.isEmpty()) return List.of();
        List<PotionEffect> out = new ArrayList<>(pts.size());
        for (com.haiman233.worldtaste.behavior.Behaviors.Potion pt : pts) {
            PotionEffectType type = PotionEffectType.getByName(pt.type);
            if (type != null) out.add(new PotionEffect(type, pt.duration, pt.amplifier, false));
            else WT.log(Lang.get("log.consumableitem.001") + pt.type);
        }
        return out;
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
     *   <li>{@code canAlwaysEat}：尘世百味恒为 true（饱食度检测已移除，创造模式同样可食用）；
     *       对齐 RSC 时期的 {@code always_eatable} 字段已废弃不再读取。</li>
     * </ul>
     * 实现经 {@link Compat#food()} 版本路由直调（1.20.5+ 直调 {@code ItemMeta#getFood/setFood}，
     * 无反射）；1.20.4- 版本段无组件 API，noop 实现按空操作处理并输出一次性低版本日志。</p>
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
        if (!hasFoodApi()) {
            if (!lowVersionLogged) {
                lowVersionLogged = true;
                WT.log(Lang.get("log.foodhelper.002"));
            }
            return true;
        }
        if (bumpNutrition && nutrition < 1) nutrition = 1;
        if (saturation < 0f) saturation = 0f;
        boolean ok = Compat.food().applyFood(stack, nutrition, saturation, canAlwaysEat, eatSeconds, false);
        if (!ok) {
            WT.log(Lang.get("log.foodhelper.003") + Compat.food().getClass().getSimpleName() + "）");
        }
        return ok;
    }
}
