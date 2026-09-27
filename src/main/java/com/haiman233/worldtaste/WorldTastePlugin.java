package com.haiman233.worldtaste;

import com.haiman233.worldtaste.load.Setup;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 尘世百味 WorldTaste —— 独立 Slimefun 附属插件主类。
 *
 * <p>由原 RSC 脚本版改写而来：内容仍来自同一组 YAML（已打包进 jar），但加载逻辑与原 JS 脚本
 * 行为全部以原生 Java 实现，不再依赖 RSC 及其 GraalVM 脚本引擎。</p>
 *
 * <p>依赖策略（与 README 的「必须 / 可选」表一致）：</p>
 * <ul>
 *   <li><b>硬依赖</b>：Slimefun、Gastronomicon（美食家）、ExoticGarden（异域花园）。
 *       缺任一个则在控制台输出「缺少 xxx，WorldTaste已自动卸载！」并禁用自身，不参与加载。
 *       注意这些仍写在 plugin.yml 的 {@code softdepend} 而非 {@code depend}——若走 Bukkit 的
 *       depend 机制，插件在 onEnable 前就会被服务器拦截，自定义中文提示将永远不会出现；
 *       放在 softdepend 还能保证「存在时先于本插件加载」，使内容引用能解析到其物品。</li>
 *   <li><b>软依赖</b>：Cultivation（农耕工艺）、InfinityExpansion（无尽贪婪）、LogiTech。
 *       缺失仅输出「缺少 xxx，部分玩法可能无法加载」后继续加载。
 *       JustEnoughGuide（JEG）虽在 softdepend 中，但其缺失已有完整降级（原版指南渲染），
 *       故不做提示。</li>
 * </ul>
 */
public final class WorldTastePlugin extends JavaPlugin implements SlimefunAddon {

    private static WorldTastePlugin instance;

    /** 硬依赖：{插件名, 中文名}，缺任一即自动卸载。 */
    private static final String[][] HARD_DEPS = {
            {"Slimefun", "粘液科技"},
            {"Gastronomicon", "美食家"},
            {"ExoticGarden", "异域花园"},
    };

    /** 软依赖：{插件名, 中文名}，缺失仅提示后继续加载。 */
    private static final String[][] SOFT_DEPS = {
            {"Cultivation", "农耕工艺"},
            {"InfinityExpansion", "无尽贪婪"},
            {"LogiTech", "LogiTech"},
    };

    @Override
    public void onEnable() {
        instance = this;
        WT.plugin = this;
        getLogger().info("尘世百味 开始加载（独立版）...");
        // 依赖检查前置：硬依赖缺失时不加载任何内容，直接卸载
        if (!checkDependencies()) return;
        try {
            Setup.loadAll();
            getLogger().info("尘世百味 加载成功");
        } catch (Throwable e) {
            getLogger().severe("尘世百味 加载过程中出现异常: " + e);
            e.printStackTrace();
        }
    }

    /**
     * 启动期依赖检查。硬依赖缺失 → 输出「缺少 xxx，WorldTaste已自动卸载！」并禁用自身，
     * 返回 false 中止加载；软依赖缺失 → 输出「缺少 xxx，部分玩法可能无法加载」后继续。
     */
    private boolean checkDependencies() {
        for (String[] dep : HARD_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().severe("缺少 " + label(dep) + "，WorldTaste已自动卸载！");
                Bukkit.getPluginManager().disablePlugin(this);
                return false;
            }
        }
        for (String[] dep : SOFT_DEPS) {
            if (Bukkit.getPluginManager().getPlugin(dep[0]) == null) {
                getLogger().warning("缺少 " + label(dep) + "，部分玩法可能无法加载");
            }
        }
        return true;
    }

    /** 依赖展示名：中文名与插件名相同（如 LogiTech）时只显示一处。 */
    private static String label(String[] dep) {
        return dep[1].equalsIgnoreCase(dep[0]) ? dep[0] : dep[0] + "（" + dep[1] + "）";
    }

    @Override
    public void onDisable() {
        getLogger().info("尘世百味 已卸载");
    }

    public static WorldTastePlugin getInstance() {
        return instance;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Override
    public String getBugTrackerURL() {
        return "https://github.com/ykcgly/WorldTaste-Plugin/issues";
    }
}
