package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.load.Yaml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 行为数据注册表：加载生成器产出的 data/*.yml（consumables/crops/fishing），
 * 供 {@link com.haiman233.worldtaste.items.ScriptItemFactory} 在注册物品时查询脚本对应的行为。
 */
public final class Behaviors {

    private Behaviors() {}

    public static final Map<String, ConsumableOpts> consumables = new HashMap<>();
    public static final Map<String, CropCfg> crops = new HashMap<>();
    /** foods.yml 带 onEat 脚本(kind=eat)的食物：itemId -> opts */
    public static final Map<String, ConsumableOpts> foodOnEat = new HashMap<>();

    /** 读取数据文件（须在物品注册前调用）。 */
    public static void loadData() {
        loadConsumables();
        loadCrops();
        FishingListener.load();
    }

    /** 注册 Bukkit 事件监听器（须在所有物品注册后调用）。 */
    public static void registerListeners() {
        org.bukkit.Bukkit.getPluginManager().registerEvents(FishingListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(CropListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(MobDropListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(BlockDrops.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(FoodConsumeListener.INSTANCE, WT.plugin);
        org.bukkit.Bukkit.getPluginManager().registerEvents(PlantGuardListener.INSTANCE, WT.plugin);
        // 榨汁盆（跳跃踩踏/铁砧砸落进度 + 容器领取）
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                com.haiman233.worldtaste.machines.JuicerBasin.Listener.INSTANCE, WT.plugin);
        // 动物奶桶挤取（空桶右键骆驼/马/羊/驴）
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                com.haiman233.worldtaste.items.MilkBucketListener.INSTANCE, WT.plugin);
        // 榨汁盆指南入口注入（点击榨汁盆配方页注入配方展示按钮，不依赖 JEG）
        com.haiman233.worldtaste.guide.JuicerGuideListener.register();
        // 酒窖指南入口（JEG 拦截 / 原版注入双模式，点击酒窖管理器进入配方展示页）
        com.haiman233.worldtaste.guide.CellarGuideListener.register();
        // 酒窖管理器机器页面（实时刷新任务 + 关闭返还监听）
        com.haiman233.worldtaste.machines.CellarMenu.register();
        // 紫颂作物定时状态的区块卸载兜底清理（普通作物零状态，无需清理）
        org.bukkit.Bukkit.getPluginManager().registerEvents(
                com.haiman233.worldtaste.items.CropBlock.ChorusStateCleanup.INSTANCE, WT.plugin);
        // JEG 大配方菜单（JEG 未安装时静默跳过）
        com.haiman233.worldtaste.jeg.JegGuideListener.register();
    }

    private static void loadConsumables() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/consumables.yml");
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            ConsumableOpts o = new ConsumableOpts();
            o.use = !"eat".equalsIgnoreCase(s.getString("kind", "use"));
            if (s.isSet("food")) o.food = s.getDouble("food");
            if (s.isSet("saturation")) o.saturation = s.getDouble("saturation");
            if (s.isSet("exhaustion")) o.exhaustion = s.getDouble("exhaustion");
            if (s.isSet("exhaustionSet")) o.exhaustionSet = s.getDouble("exhaustionSet");
            if (s.isSet("absorption")) o.absorption = s.getDouble("absorption");
            o.gameMode = s.getString("gameMode");
            if (s.isSet("remainingAirAdd")) o.remainingAirAdd = s.getInt("remainingAirAdd");
            if (s.isSet("foodSet")) o.foodSet = s.getInt("foodSet");
            if (s.isSet("saturationSet")) o.saturationSet = (float) s.getDouble("saturationSet");
            o.requireHungry = s.getBoolean("requireHungry", false);
            if (s.isSet("satRegen")) o.satRegen = s.getInt("satRegen");
            if (s.isSet("unsatRegen")) o.unsatRegen = s.getInt("unsatRegen");
            if (s.isSet("starvation")) o.starvation = s.getInt("starvation");
            if (s.isSet("maxAir")) o.maxAir = s.getInt("maxAir");
            if (s.isSet("remainingAir")) o.remainingAir = s.getInt("remainingAir");
            if (s.isSet("freezeTicks")) o.freezeTicks = s.getInt("freezeTicks");
            if (s.isSet("randomFood")) o.randomFood = s.getInt("randomFood");
            String offTool = s.getString("offhandTool");
            if (offTool != null && !offTool.isEmpty()) {
                o.offhandTool = Material.matchMaterial(offTool.trim().toUpperCase(java.util.Locale.ROOT));
            }
            o.consumeOffhand = s.getBoolean("consumeOffhand", false);
            if (s.isList("potions")) {
                for (Map<?, ?> pm : s.getMapList("potions")) {
                    Object t = pm.get("type");
                    Object d = pm.get("duration");
                    Object a = pm.get("amplifier");
                    if (t != null && d instanceof Number && a instanceof Number) {
                        o.potions.add(new Potion(t.toString(), ((Number) d).intValue(), ((Number) a).intValue()));
                    }
                }
            }
            o.message = s.getString("message");
            consumables.put(name, o);
        }
        WT.plugin.getLogger().info("行为数据: consumables=" + consumables.size());
    }

    private static void loadCrops() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/crops.yml");
        int skip = 0;
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            try {
                CropCfg c = new CropCfg();
                Material m = Material.matchMaterial(s.getString("material", "WHEAT"));
                c.material = m != null ? m : Material.WHEAT;
                c.maxAge = s.getInt("maxAge", 7);
                c.growMs = s.getLong("growMs", 120000L);
                if (s.isList("drops")) {
                    for (Map<?, ?> mm : s.getMapList("drops")) {
                        // 显式校验类型：缺 chance 或非数值曾导致 NPE/CCE 逃出 loadData、
                        // 连累其后的全部加载(items/foods/machines…)被 onEnable 顶层 catch 跳过。
                        Object id = mm.get("id");
                        Object ch = mm.get("chance");
                        if (id instanceof String && ch instanceof Number) {
                            c.drops.add(new CropDrop((String) id, ((Number) ch).doubleValue(), 0));
                        } else {
                            WT.log("crop " + name + " 的 drops 项缺少 id/chance，跳过该项");
                        }
                    }
                } else if (s.isList("weightedDrops")) {
                    for (Map<?, ?> mm : s.getMapList("weightedDrops")) {
                        Object id = mm.get("id");
                        Object w = mm.get("weight");
                        if (id instanceof String && w instanceof Number) {
                            double wv = ((Number) w).doubleValue();
                            c.drops.add(new CropDrop((String) id, 0, wv));
                            c.weightTotal += wv; // R8: load 期预算权重总和（对齐 R4）
                        } else {
                            WT.log("crop " + name + " 的 weightedDrops 项缺少 id/weight，跳过该项");
                        }
                    }
                    c.weighted = true;
                }
                // 可选种植要求：plantOn（单个材质名或列表），无效项跳过；空列表视为不限制
                if (s.isList("plantOn")) {
                    List<Material> list = new ArrayList<>();
                    for (String mat : s.getStringList("plantOn")) {
                        Material pm = Material.matchMaterial(mat);
                        if (pm != null) list.add(pm);
                    }
                    if (!list.isEmpty()) c.plantOn = list;
                } else if (s.isString("plantOn")) {
                    Material pm = Material.matchMaterial(s.getString("plantOn"));
                    if (pm != null) { c.plantOn = new ArrayList<>(); c.plantOn.add(pm); }
                }
                // 种子掉落配置：cropId 为该作物的种子物品 id（crops.yml 全量覆盖）；
                // 未成熟破坏必掉 1 个种子；成熟破坏额外按 seedDropChance 概率掉 1..seedDropMax 个种子。
                c.cropId = s.getString("cropId");
                c.seedDropChance = s.getDouble("seedDropChance", 0.5);
                c.seedDropMax = Math.max(1, s.getInt("seedDropMax", 3));
                crops.put(name, c);
            } catch (Exception e) {
                WT.log("crop " + name + " 解析失败，跳过: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("行为数据: crops=" + crops.size() + (skip > 0 ? ", 跳过 " + skip : ""));
    }

    /** 食物消耗参数（对应原 WT_eatConsumable opts，并扩展覆盖独立脚本的空气/冻结/药水等）。 */
    public static final class ConsumableOpts {
        public boolean use = true;
        public Double food;
        public Double saturation;
        public Double exhaustion;
        public Double exhaustionSet;
        public Double absorption;
        public String gameMode;
        public Integer remainingAirAdd;
        public Integer foodSet;
        public Float saturationSet;
        public boolean requireHungry;
        public Integer satRegen;
        public Integer unsatRegen;
        public Integer starvation;
        public Integer maxAir;
        public Integer remainingAir;
        public Integer freezeTicks;
        public Integer randomFood;
        public Material offhandTool;
        public boolean consumeOffhand;
        public final List<Potion> potions = new ArrayList<>();
        public String message;
    }

    public static final class Potion {
        public final String type;
        public final int duration;
        public final int amplifier;
        public Potion(String type, int duration, int amplifier) {
            this.type = type; this.duration = duration; this.amplifier = amplifier;
        }
    }

    /** 按作物材质推断默认种植要求（原版机制）。显式 plantOn 配置优先于推断。 */
    static List<Material> inferPlantOn(Material m) {
        switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, MELON_STEM, PUMPKIN_STEM, PITCHER_CROP, TORCHFLOWER_CROP:
                return List.of(Material.FARMLAND);
            case NETHER_WART:
                return List.of(Material.SOUL_SAND, Material.SOUL_SOIL);
            case SUGAR_CANE:
                return List.of(Material.SAND, Material.RED_SAND, Material.DIRT, Material.GRASS_BLOCK);
            case CACTUS:
                return List.of(Material.SAND, Material.RED_SAND);
            case COCOA:
                return List.of(Material.JUNGLE_LOG, Material.JUNGLE_WOOD, Material.STRIPPED_JUNGLE_LOG, Material.STRIPPED_JUNGLE_WOOD);
            case SWEET_BERRY_BUSH:
                return List.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT, Material.MUD, Material.FARMLAND);
            default:
                return null;
        }
    }

    /** 作物参数（对应原 WT_setupCrop cfg）。 */
    public static final class CropCfg {
        public Material material;
        public int maxAge;
        public long growMs;
        public String stages;
        public final List<CropDrop> drops = new ArrayList<>();
        public boolean weighted = false;
        /** 种植要求：允许种植在其上的方块列表；null 表示不限制（对应 crops.yml 可选 plantOn 字段）。 */
        public List<Material> plantOn;
        /** 种子物品 id（crops.yml cropId）：未成熟破坏必掉 1 个；成熟破坏按概率额外掉 1..seedDropMax 个。 */
        public String cropId;
        /** 成熟破坏时额外种子掉落的概率（默认 0.5；crops.yml seedDropChance 可调）。 */
        public double seedDropChance = 0.5;
        /** 成熟破坏时额外种子掉落的最大数量（1..seedDropMax 均匀随机，默认 3；crops.yml seedDropMax 可调）。 */
        public int seedDropMax = 3;

        /** 实际种植要求：显式 plantOn 优先，否则按材质推断（原版机制）；null 表示不限制。 */
        public List<Material> resolvedPlantOn() {
            if (plantOn != null) return plantOn;
            return Behaviors.inferPlantOn(material);
        }
        /** 加权掉落的权重总和，load 期一次预算（对齐 R4 FishingListener.Bait.total）。
         *  CropBlock.onBreak 直接用此值，消除每次收获对 drops 的求和（O(n)→O(1)）。仅 weighted 作物有意义。 */
        public double weightTotal = 0;
    }

    public static final class CropDrop {
        public final String id;
        public final double chance;
        public final double weight;
        public CropDrop(String id, double chance, double weight) {
            this.id = id; this.chance = chance; this.weight = weight;
        }
    }
}
