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
 * <p>开关项：酿造工艺总开关 {@code brewing.enabled}（关闭时见 {@link #blockItem}）与
 * 食物行为组 {@code food.native-eat} / {@code food.legacy-upgrade} /
 * {@code food.ignore-place-veto}、调试组 {@code debug.food}。其中
 * {@code food.native-eat} 支持旧配置自动补写（见 {@link #resolveNativeEat}）。</p>
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
     * 原版进食机制总开关（food.native-eat）。
     *
     * <p>取值规则（自动补写机制，1.9.9 起）：</p>
     * <ul>
     *   <li>插件目录<b>不存在</b> config.yml（首次启动 / 删除后重新生成）：释放 jar 内默认
     *       模板，取其值 <b>true</b>——新生成的配置默认开启原版进食；</li>
     *   <li>磁盘已有旧配置但条目<b>缺失或值无效</b>（老版本配置升级）：自动补写为
     *       <b>false</b> 并在控制台输出提示——旧配置的服主多半基于旧的插件结算行为调服，
     *       原版进食属新增行为，默认关闭更稳妥；需要时手动改 true 并重启。</li>
     *   <li>条目存在且为布尔 → 照常读取。</li>
     * </ul>
     *
     * <p>true：食物右键交由原版进食管线（进食/饮用动画、咀嚼音、时长原生），脚本效果在
     * {@code PlayerItemConsumeEvent} 补足。物品注入 FoodComponent + consumable 组件
     * （1.21.2+，含饮品 DRINK 动画），
     * {@link com.haiman233.worldtaste.items.ConsumableItem} 处理器只做「放置/进食」分流：
     * 非潜行指向可放置落点时仅 DENY 方块交互、放行物品使用，由服务端直接开始进食。
     * 饱食度检测已整体移除：所有食物 {@code canAlwaysEat=true}，饱食度满（含创造模式
     * 恒 20）同样可食用。服务端低于 1.20.5（无组件 API）时自动回退旧逻辑，无需改配置。</p>
     *
     * <p>false：保持旧的插件结算逻辑（右键瞬间完成食用，无进食动画），且不给物品注入
     * 可食组件（维持其不可被原版白吃的旧行为）。</p>
     */
    public static boolean nativeEat = true;

    /**
     * 旧存档食物自动升级开关（food.legacy-upgrade，默认 false）。
     *
     * <p>true：1.9.7 之前获取的存量食物（无可食组件）在<b>首次右键</b>时自动补齐
     * FoodComponent + consumable 组件，此后走正常原版进食。每次右键多一次组件读取检测。</p>
     *
     * <p>false（默认）：不做检测——存量食物不能原版进食（非潜行右键地面也只阻止放置），
     * 潜行仍可正常放置；重新合成/重新获取的新物品不受影响。关表可省去监听内的组件检测开销。</p>
     */
    public static boolean legacyUpgrade = false;

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
        boolean generated = false;
        if (!file.exists()) {
            try {
                WT.plugin.saveResource(FILE, false);
                generated = file.exists();
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
        nativeEat = resolveNativeEat(y, file, generated);
        legacyUpgrade = y.getBoolean("food.legacy-upgrade", false);
        debugFood = y.getBoolean("debug.food", false);

        WT.plugin.getLogger().info("config.yml: 酿造工艺 " + (brewingEnabled ? "已启用" : "已禁用"));
        WT.plugin.getLogger().info("config.yml: 食物放置外部否决强制放行 " + (foodIgnorePlaceVeto ? "开启" : "关闭"));
        WT.plugin.getLogger().info("config.yml: 食物原版进食机制 " + (nativeEat ? "开启" : "关闭（插件结算）"));
        WT.plugin.getLogger().info("config.yml: 旧存档食物自动升级 " + (legacyUpgrade ? "开启" : "关闭"));
    }

    /**
     * 解析 food.native-eat（含自动补写机制）：
     * <ul>
     *   <li>配置文件是本次新生成的（原先不存在，刚从 jar 释放）→ 采用模板默认 true；</li>
     *   <li>磁盘旧配置缺条目或值非布尔（老版本配置升级）→ 补写为 false 保存并在控制台
     *       warning 提示（原版进食是新增行为，旧配置默认关闭更稳妥）；</li>
     *   <li>条目正常 → 照常读取；磁盘文件缺失且释放失败（y 来自 jar 内置）→ 模板值 true。</li>
     * </ul>
     */
    private static boolean resolveNativeEat(YamlConfiguration y, File file, boolean generated) {
        if (generated) return true;
        if (!file.exists() || (y.isSet("food.native-eat") && y.isBoolean("food.native-eat"))) {
            return y.getBoolean("food.native-eat", true);
        }
        // 旧配置升级：补写条目为 false（原版进食默认关闭），下次启动直接读到显式值
        y.set("food.native-eat", false);
        try {
            y.save(file);
            WT.plugin.getLogger().warning("config.yml 缺少 food.native-eat 条目（或值无效），"
                    + "已自动补写为 false——食物原版进食机制默认关闭；如需开启请手动改为 true 后重启");
        } catch (Exception e) {
            WT.log("config.yml 自动补写 food.native-eat 失败（本次仍按 false 生效）: " + e);
        }
        return false;
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
