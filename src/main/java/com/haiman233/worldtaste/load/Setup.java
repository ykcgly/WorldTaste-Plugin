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
        GroupLoader.load();
        RecipeTypes.load();
        preloadDisplays();
        com.haiman233.worldtaste.behavior.Behaviors.loadData();
        ItemsLoader.load();
        FoodsLoader.load();
        // 榨汁盆配方（产物/投入物可引用已注册物品，须在物品注册后加载）
        JuicerLoader.load();
        // 糖分值配置（材料 id → 糖分，运行期读取）
        SugarLoader.load();
        // 酒窖配方（cellar.yml：配方表 + 命名功能开关）
        CellarLoader.load();
        com.haiman233.worldtaste.machines.WineBottle.register(WT.group("ws_niangzaogongyi"));
        com.haiman233.worldtaste.machines.SweetnessPaper.register();
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
        // R6：所有内容/行为文件加载完毕，释放 Yaml 文件名缓存的解析树（长稳：避免长期持有 ~MB 级解析对象树）。
        // 经核查无 Loader 以字段持久持有 ConfigurationSection，registerListeners 也不再访问 YAML，释放安全。
        Yaml.clearCache();
        // R7：释放头颅贴图(PlayerSkin)去重缓存（Read 仅加载期使用，运行期不再调 Read.item/recipe）。
        Read.clearSkinCache();
        // R9：释放 preload 展示物品表（数千个 ItemStack）。经核查全部读取方（Read.resolve 的
        // material_type:slimefun 回退、RegisterConditions 的 itemexist、各 Loader 的展示堆获取）
        // 均在本次 loadAll 流程内，运行期无引用，可安全释放（长稳省内存）。
        WT.preload.clear();
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
