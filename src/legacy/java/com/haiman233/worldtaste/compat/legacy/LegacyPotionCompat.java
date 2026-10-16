package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.PotionCompat;
import org.bukkit.potion.PotionType;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * legacy（1.20.2+）药水实现：Paper 1.20.6 编译、{@code PotionMeta#getBasePotionType}
 * 直调（无反射）。{@code PotionMeta} 是接口、方法描述符两侧一致，
 * 1.20.2 ~ 1.21.x 全区间二进制兼容；1.20.0/1.20.1 由 Compat 路由到 NoopPotionCompat。
 */
public final class LegacyPotionCompat implements PotionCompat {

    public static final LegacyPotionCompat INSTANCE = new LegacyPotionCompat();

    private LegacyPotionCompat() {}

    @Override
    public PotionType basePotionType(PotionMeta pm) {
        return pm == null ? null : pm.getBasePotionType();
    }
}
