package com.haiman233.worldtaste.compat;

import org.bukkit.inventory.ItemStack;

/**
 * 食物组件兼容层接口。实现分三档，由 {@link Compat} 按服务器版本路由：
 * <ul>
 *   <li><b>modern</b>（1.21.2+，{@code compat.modern} 源集，Paper 1.21.11 编译）：
 *       FoodComponent 直调 + consumable 组件（DataComponent API）直调；</li>
 *   <li><b>legacy</b>（1.20.5 ~ 1.21.1，{@code compat.legacy} 源集，Paper 1.20.6 编译）：
 *       FoodComponent 直调（1.20.5 起存在 {@code ItemMeta#getFood}）；该版本段无
 *       consumable 组件概念 → {@link #hasConsumable} 恒 true、{@link #applyConsumable} 空操作；</li>
 *   <li><b>noop</b>（1.20.4-，main 内 {@link NoopFoodCompat}）：无任何组件 API，
 *       全部回退插件结算（保持旧行为）。</li>
 * </ul>
 * 所有实现均为对应版本 API 的<b>编译期直调</b>，禁止反射。
 */
public interface FoodCompat {

    /**
     * FoodComponent API（ItemMeta#getFood，1.20.5+）在当前服务端是否可用。
     * false 时 {@link #applyFood} 为空操作，foods.yml 的营养/饱食度组件不生效（脚本食物效果不受影响）。
     */
    boolean hasFoodApi();

    /**
     * 应用 FoodComponent（营养/饱食/可随时食用/食用时长）。
     *
     * @param stack          目标物品（原地修改 meta）
     * @param nutrition      营养值（已由调用方完成 clamp）
     * @param saturation     饱和恢复量（1.20.5+ 为直接恢复量，非旧 modifier）
     * @param canAlwaysEat   饱食度满时是否仍可吃
     * @param eatSeconds     食用时长（&gt;0 时写入 {@code setEatSeconds}，否则保持默认 1.6s）
     * @param bumpNutrition  true = 营养值 &lt;1 时提升为 1（对齐 RSC FoodReader 语义）
     * @return 是否成功（失败返回 false 供上层统计/告警）
     */
    boolean applyFood(ItemStack stack, int nutrition, float saturation, boolean canAlwaysEat,
                      float eatSeconds, boolean bumpNutrition);

    /**
     * 物品是否已带 consumable 组件（可被原版进食）。
     * legacy 版本段（&lt;1.21.2）恒 true——FoodComponent 本身即可食，无需懒升级。
     */
    boolean hasConsumable(ItemStack stack);

    /**
     * 注入 consumable 组件（动画/音效/食用时长）。legacy 版本段为带防护的空操作
     * （写不进去也不影响 FoodComponent 可食性）。
     *
     * @param animation      "eat" 或 "drink"
     * @param soundKey       音效资源键（null = 组件默认音效）
     */
    void applyConsumable(ItemStack stack, float consumeSeconds, String animation, String soundKey);
}
