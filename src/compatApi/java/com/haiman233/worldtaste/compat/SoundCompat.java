package com.haiman233.worldtaste.compat;

import org.bukkit.Sound;

/**
 * 音效常量解析兼容层（脚本 {@code sound: entity.generic.drink} → Sound）。
 *
 * <p>{@code Sound} 在 1.21.3 起由枚举改为接口（{@code Sound.valueOf} 消失），
 * 两侧运行形态不同，必须按版本路由、编译期直调：</p>
 * <ul>
 *   <li><b>legacy</b>（≤1.21.2，{@code compat.legacy} 源集，Paper 1.20.6 编译）：
 *       枚举 {@code Sound.valueOf(ENUM_NAME)}；</li>
 *   <li><b>modern</b>（1.21.3+，{@code compat.modern} 源集，Paper 1.21.11 编译）：
 *       Registry 按资源键查找。</li>
 * </ul>
 * <p>返回的 {@code Sound} 类型描述符（{@code Lorg/bukkit/Sound;}）两侧一致，
 * 调用方（Behaviors 播放音效）无需感知版本。</p>
 */
public interface SoundCompat {

    /**
     * 原版点分资源键（entity.generic.drink）→ Sound 常量；无效键返回 null（调用方回退默认音效）。
     */
    Sound resolve(String key);
}
