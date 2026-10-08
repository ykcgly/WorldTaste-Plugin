package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 全局配置（config.yml）：玩法总开关等与内容无关的开关项。
 *
 * <p>加载顺序：优先读取 {@code plugins/WorldTaste/config.yml}（首次启动由
 * {@code saveResource} 从 jar 释放默认文件），读取失败或文件缺失时回退 jar 内置配置。
 * 这样管理员既能直接改插件目录下的文件，也不必担心文件被删坏导致插件起不来。</p>
 *
 * <p>目前仅一项：酿造工艺总开关 {@code brewing.enabled}。关闭时见
 * {@link #blockItem}——酿造分组下的物品不再注册，但分组按钮保留并标注「已禁用」。</p>
 */
public final class WTConfig {

    private WTConfig() {}

    /** 配置文件名（jar 内置 + 插件数据目录同名）。 */
    private static final String FILE = "config.yml";

    /** 酿造工艺分组 id（groups.yml 的 ws_niangzaogongyi）。 */
    public static final String BREWING_GROUP = "ws_niangzaogongyi";

    /**
     * 酿造工艺「已禁用」占位说明页 id（items.yml）。
     *
     * <p>必须保留这一个可见物品的缘由：Slimefun 的 {@code ItemGroup#isVisible} 对「一个物品
     * 都没有」的分组直接返回 false，而 {@code SubItemGroup#isVisibleInNested} 走的正是它。
     * 酿造玩法关闭后若把分组内容全部屏蔽，分组即成空组、按钮被指南整条过滤掉，玩家看不到
     * 既定的「已禁用」标注。故关闭时注册这一个占位说明页（开启时跳过）保住按钮。</p>
     */
    public static final String BREWING_DISABLED_NOTICE = "WT_NIANZAO_DISABLED";

    /**
     * 酿造工艺总开关（brewing.enabled）。
     *
     * <p>缺省值与 config.yml 默认保持一致——**默认 true（默认开启酿造玩法）**，
     * 不需要该玩法的服主可在 plugins/WorldTaste/config.yml 里改为 false。</p>
     */
    public static boolean brewingEnabled = true;

    /** 关闭时追加在分组名后的字样（brewing.disabled-suffix）。 */
    public static String disabledSuffix = "&r&c&l已禁用";

    /**
     * 食物放置事件被外部插件否决时是否强制放行（food.ignore-place-veto，默认 true）。
     *
     * <p>实测服内多个附属插件会在 {@code BlockPlaceEvent} 上否决尘世百味的自定义头颅食物，
     * 导致潜行右键永远放不出来（与权限/领地无关）。true 时本插件在 HIGHEST 优先级取消针对
     * <b>本插件食物</b>的外部否决（放置继续走原版管线，手感不变）；false 时尊重外部否决，
     * debug 模式下会列出候选插件名单。</p>
     */
    public static boolean foodIgnorePlaceVeto = true;

    /** 食物右键决策的调试日志（debug.food，默认 false）。 */
    public static boolean debugFood = false;

    /**
     * 读取配置。必须在 {@link GroupLoader#load()} 之前调用——分组名是否追加
     * 「已禁用」取决于本开关。
     */
    public static void load() {
        YamlConfiguration y = null;
        File file = new File(WT.plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            try {
                WT.plugin.saveResource(FILE, false);
            } catch (Exception e) {
                WT.log("释放默认 " + FILE + " 失败（将使用 jar 内置配置）: " + e);
            }
        }
        if (file.exists()) {
            try {
                y = YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                WT.log("读取 plugins/WorldTaste/" + FILE + " 失败，回退 jar 内置配置: " + e);
                y = null;
            }
        }
        if (y == null) y = Yaml.loadResource(WT.plugin, FILE);

        brewingEnabled = y.getBoolean("brewing.enabled", true);
        disabledSuffix = y.getString("brewing.disabled-suffix", "&r&c&l已禁用");
        if (disabledSuffix == null) disabledSuffix = "";

        foodIgnorePlaceVeto = y.getBoolean("food.ignore-place-veto", true);
        debugFood = y.getBoolean("debug.food", false);

        WT.plugin.getLogger().info("config.yml: 酿造工艺 " + (brewingEnabled ? "已启用" : "已禁用"));
        WT.plugin.getLogger().info("config.yml: 食物放置外部否决强制放行 " + (foodIgnorePlaceVeto ? "开启" : "关闭"));
    }

    /** 酿造玩法是否已关闭。 */
    public static boolean brewingDisabled() {
        return !brewingEnabled;
    }

    /** 分组 id 是否为酿造工艺分组。 */
    public static boolean isBrewingGroup(String groupId) {
        return groupId != null && BREWING_GROUP.equalsIgnoreCase(groupId.trim());
    }

    /**
     * 该物品是否应被跳过注册：仅当酿造玩法关闭且该物品属于酿造工艺（分组或显式 id）时返回 true。
     *
     * <p>判定以分组为主（酿造内容全部挂在 ws_niangzaogongyi 下），{@code itemId} 仅作
     * 兜底——物品本体由 WineBottle 等 Java 侧注册时也能一并拦下。</p>
     *
     * <p>例外：{@link #BREWING_DISABLED_NOTICE} 占位说明页不受本拦截影响，由调用方按其
     * 「仅在关闭时注册」的特殊语义单独处理。</p>
     */
    public static boolean blockItem(String groupId, String itemId) {
        if (!brewingDisabled()) return false;
        if (BREWING_DISABLED_NOTICE.equalsIgnoreCase(itemId)) return false;
        if (isBrewingGroup(groupId)) return true;
        return itemId != null && (itemId.equalsIgnoreCase("WT_WINE") || itemId.equalsIgnoreCase("WT_ZHAPEN")
                || itemId.equalsIgnoreCase("WT_JIUJIAO") || itemId.equalsIgnoreCase("WT_WENDU"));
    }

    /**
     * 给分组展示物品追加「已禁用」字样（仅酿造分组且玩法关闭时生效）。
     *
     * <p>追加内容先 {@code &r} 复位：分类名末尾常带 {@code &k}（乱码）等格式码，
     * 直接拼接会把「已禁用」也一起染色/乱码。</p>
     */
    public static ItemStack applyDisabledSuffix(String groupKey, ItemStack display) {
        if (display == null || disabledSuffix.isEmpty()) return display;
        if (!brewingDisabled() || !isBrewingGroup(groupKey)) return display;
        ItemStack out = display.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return display;
        String base = meta.hasDisplayName() ? meta.getDisplayName() : "";
        meta.setDisplayName(base + " " + Colors.c(disabledSuffix));
        out.setItemMeta(meta);
        return out;
    }
}
