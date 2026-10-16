package com.haiman233.worldtaste.compat;

import org.bukkit.potion.PotionType;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * 药水基础类型读取兼容层（酒窖水瓶判定用）。
 *
 * <p>{@code PotionMeta#getBasePotionType} 为 1.20.2+ API；1.20.0/1.20.1 上该方法不存在
 * （原实现以反射双路径兼容，现按「不许用反射」约束改为版本路由）：</p>
 * <ul>
 *   <li><b>legacy/modern 共用</b>（1.20.2+，{@code compat.legacy} 源集，Paper 1.20.6 编译）：
 *       直调 {@code getBasePotionType}——{@code PotionMeta} 是接口、方法描述符两侧一致，
 *       1.20.2 ~ 1.21.x 全区间二进制兼容；</li>
 *   <li><b>noop</b>（&lt;1.20.2，main 内 {@link NoopPotionCompat}）：恒返回 null
 *       （水瓶识别退化为「非水瓶」，酒窖功能在该古董版本段本就无组件 API 支撑）。</li>
 * </ul>
 */
public interface PotionCompat {

    /**
     * 读取药水基础药水类型；未设置时返回 null。
     */
    PotionType basePotionType(PotionMeta pm);
}
