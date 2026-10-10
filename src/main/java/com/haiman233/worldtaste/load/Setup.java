package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 内容加载编排：按依赖顺序注册 groups → recipe_types → 预加载展示物品 → 各内容文件。 */
public final class Setup {

    private Setup() {}

    /** 含有可被其它配方以 material_type:slimefun 引用的“物品”的文件（需预加载展示堆）。 */
    private static final String[] ITEM_FILES = {
        "items.yml", "machines.yml", "foods.yml", "mob_drops.yml", "geo_resources.yml",
        "recipe_machines.yml", "mb_machines.yml", "linked_recipe_machines.yml",
        "template_machines.yml", "workbenches.yml"
    };

    public static void loadAll() {
        long t = System.currentTimeMillis();
        // 「物品未找到」折叠上报：加载开始清零、结尾汇总（未装对应附属时引用可达数千处，不逐条刷屏）
        MissingItems.reset();
        // 全局配置（config.yml）须最先加载：分组名是否追加「已禁用」、酿造内容是否注册都取决于它
        WTConfig.load();
        GroupLoader.load();
        RecipeTypes.load();
        preloadDisplays();
        com.haiman233.worldtaste.behavior.Behaviors.loadData();
        ItemsLoader.load();
        FoodsLoader.load();
        // 酿造工艺总开关（config.yml brewing.enabled）：关闭时整条酿造玩法不加载——
        // 榨汁/酒窖配方、糖分表、果酒与甜度试纸物品全部跳过，分组按钮保留并标注「已禁用」
        if (WTConfig.brewingEnabled) {
            // 榨汁盆配方（产物/投入物可引用已注册物品，须在物品注册后加载）
            JuicerLoader.load();
            // 糖分值配置（材料 id → 糖分，运行期读取）
            SugarLoader.load();
            // 酒窖配方（cellar.yml：配方表 + 命名功能开关）
            CellarLoader.load();
            com.haiman233.worldtaste.machines.WineBottle.register(WT.group("ws_niangzaogongyi"));
            com.haiman233.worldtaste.machines.SweetnessPaper.register();
        }
        MobDropsLoader.load();
        MenuLoader.load();
        RecipeMachineLoader.load();
        WorkbenchLoader.load();
        MultiBlockLoader.load();
        TemplateLoader.load();
        GeoLoader.load();
        com.haiman233.worldtaste.behavior.Behaviors.registerListeners();
        // 异域花园酒精度联动：启动期探测并在日志输出联动状态（未装异域花园时降级为风味文本）
        com.haiman233.worldtaste.hook.ExoticGardenHook.init();
        // 外观反查索引：所有物品（含 lateInit/机器/多方块）注册完毕后构建一次，供运行期
        // findRegisteredByAppearance 按材质分桶 O(1) 查询，避免放置事件等高频路径全量扫描
        com.haiman233.worldtaste.util.Stacks.buildAppearanceIndex();
        // 禁用物品配方过滤：物品注册全部结束后按 PDC id 复查机器配方，剔除引用
        // 未注册（如 brewing.enabled=false 的 preload 幽灵）或已禁用（/sf disable）物品的配方，
        // 使其不再可合成，也不出现在大配方展示与配方补全界面
        com.haiman233.worldtaste.machines.WTRecipeMachine.filterDisabledRecipes();
        // R6：所有内容/行为文件加载完毕，释放 Yaml 文件名缓存的解析树（长稳：避免长期持有 ~MB 级解析对象树）。
        // 经核查无 Loader 以字段持久持有 ConfigurationSection，registerListeners 也不再访问 YAML，释放安全。
        Yaml.clearCache();
        // R7：释放头颅贴图(PlayerSkin)去重缓存（Read 仅加载期使用，运行期不再调 Read.item/recipe）。
        Read.clearSkinCache();
        // R9：释放 preload 展示物品表（数千个 ItemStack）。经核查全部读取方（Read.resolve 的
        // material_type:slimefun 回退、RegisterConditions 的 itemexist、各 Loader 的展示堆获取）
        // 均在本次 loadAll 流程内，运行期无引用，可安全释放（长稳省内存）。
        WT.preload.clear();
        // 「物品未找到」汇总：普通缺失折叠为一行（特殊物品已在发现时单独告警）
        MissingItems.report();
        WT.plugin.getLogger().info("基础内容加载完成，耗时 " + (System.currentTimeMillis() - t) + "ms");
    }

    /** 第一遍：把各物品/机器的展示堆加入 WT.preload，使后续配方解析能跨文件按 id 引用。 */
    private static void preloadDisplays() {
        for (String file : ITEM_FILES) {
            YamlConfiguration y = Yaml.loadResource(WT.plugin, file);
            // 逐条 try/catch 故障隔离：Read.item 经 PlayerHead/PlayerSkin.fromURL|fromBase64|fromHashCode
            // 等路径，单条坏展示数据可能抛异常；若无隔离会中止 preloadDisplays → loadAll → 其后
            // items/foods/机器等全部因 preload 查空而跳过，插件近乎空载启用。
            for (String id : y.getKeys(false)) {
                try {
                    ConfigurationSection s = y.getConfigurationSection(id);
                    if (s == null) continue;
                    ConfigurationSection itemSec = s.getConfigurationSection("item");
                    if (itemSec == null) continue;
                    ItemStack display = Read.item(itemSec, false);
                    if (display != null) {
                        String effId = s.getString("id_alias", id).toUpperCase(java.util.Locale.ROOT);
                        WT.preload.put(effId, display);
                    }
                } catch (Exception e) {
                    WT.log("预加载展示物品 " + id + " 失败，跳过: " + e);
                }
            }
        }
    }
}
