package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.SoundCompat;
import java.util.Locale;
import org.bukkit.Sound;

/**
 * legacy（≤1.21.2）音效实现：Paper 1.20.6 编译，{@code Sound} 在该版本段为<b>枚举</b>，
 * 直接 {@code Sound.valueOf(ENUM_NAME)}（无反射；1.21.3+ 接口形态由 modern 源集实现承担）。
 */
public final class LegacySoundCompat implements SoundCompat {

    public static final LegacySoundCompat INSTANCE = new LegacySoundCompat();

    private LegacySoundCompat() {}

    @Override
    public Sound resolve(String key) {
        if (key == null || key.isEmpty()) return null;
        String name = key.trim().toUpperCase(Locale.ROOT).replace('.', '_');
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
