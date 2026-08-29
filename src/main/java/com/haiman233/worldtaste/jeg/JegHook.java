package com.haiman233.worldtaste.jeg;

/**
 * JEG（JustEnoughGuide）集成：仅负责两件事——
 * <ul>
 *   <li>检测 JEG 是否安装（供 {@link JegGuideListener} 决定是否注册指南事件拦截）；</li>
 *   <li>打开 JEG 指南主菜单（大配方菜单的返回按钮用；反射调用，JEG 未装时静默）。</li>
 * </ul>
 *
 * <p>机器配方补全不集成 JEG 的 RecipeCompletable（曾尝试后弃用）：JEG Build 205 对绑定槽配方
 * 存在循环左移缺陷（填充顺序与 GUI 布局不一致），其补全按钮也会与自定义补全按钮重复，
 * 统一走 {@link com.haiman233.worldtaste.guide.RecipeFillMenu} 自定义补全。</p>
 */
public final class JegHook {

    private JegHook() {}

    /** JEG 是否可用（检测其指南事件类是否存在——本插件的 JEG 集成仅依赖事件与指南打开 API）。 */
    public static boolean available() {
        try {
            Class.forName("com.balugaq.jeg.api.objects.events.GuideEvents");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开 JEG 指南主菜单（大配方菜单返回用；JEG 未安装或 API 变更时静默）。 */
    public static void openGuide(org.bukkit.entity.Player p) {
        try {
            Class<?> clazz = Class.forName("com.balugaq.jeg.utils.GuideUtil");
            Class<?> modeCls = Class.forName("io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode");
            Object mode = io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide.class
                    .getMethod("getDefaultMode").invoke(null);
            clazz.getMethod("openMainMenuAsync", org.bukkit.entity.Player.class, modeCls, int.class)
                    .invoke(null, p, mode, 1);
        } catch (Throwable ignored) {
            // JEG 未安装或 API 变更：静默
        }
    }
}
