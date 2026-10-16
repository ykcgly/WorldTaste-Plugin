package com.haiman233.worldtaste.compat;

import org.bukkit.potion.PotionType;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * 1.20.0/1.20.1 版本段的药水实现：{@code PotionMeta#getBasePotionType} 为 1.20.2+ API，
 * 该版本段不存在（1.20.4- 的 {@code getBasePotionData} 又已在 1.20.5 移除，无法用同一
 * 无反射实现覆盖两端）→ 恒返回 null，水瓶识别退化为「非水瓶」。
 */
public final class NoopPotionCompat implements PotionCompat {

    public static final NoopPotionCompat INSTANCE = new NoopPotionCompat();

    private NoopPotionCompat() {}

    @Override
    public PotionType basePotionType(PotionMeta pm) {
        return null;
    }
}
