package com.haiman233.worldtaste.compat.modern;

import com.haiman233.worldtaste.compat.SoundCompat;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;

/**
 * modern（1.21.3+）音效实现：Paper 1.21.11 编译，{@code Sound} 在该版本段为<b>接口</b>
 * （{@code Sound.valueOf} 消失），改走 Registry 按资源键查找（无反射）。
 */
public final class ModernSoundCompat implements SoundCompat {

    public static final ModernSoundCompat INSTANCE = new ModernSoundCompat();

    private ModernSoundCompat() {}

    @Override
    public Sound resolve(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            NamespacedKey k = NamespacedKey.minecraft(key.trim().toLowerCase(Locale.ROOT));
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(k);
        } catch (IllegalArgumentException e) {
            return null; // 键含非法字符
        }
    }
}
