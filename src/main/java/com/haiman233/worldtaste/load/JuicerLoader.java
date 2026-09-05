package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 加载 juicer.yml 榨汁盆配方（须在 items/foods 物品注册后调用，产物/投入物可按
 * material_type:slimefun 引用已注册物品）。
 *
 * <pre>
 * # 头部混合配置
 * mix:
 *   recipes: [PINGGUO_ZHAPEN]   # 可混合配方：其投入物可任意组合投入盆中混合榨汁
 *   progress: 10                # 混合榨汁所需踩踏进度
 *   bottle: {...}               # 瓶装混合产物模板（item 段同构）
 *   bucket: {...}               # 桶装混合产物模板
 *
 * # 普通配方：多材料 + 数量投入
 * KEY:
 *   ingredient:
 *     - APPLE:3                 # id:数量（id 为材质名或粘液物品 id）
 *     - WT_PUTAOTI:2
 *   type: player,anvil          # 逗号分隔多值：踩踏与铁砧砸击均可
 *   progress: 10
 *   yield: 3                    # 榨好后的接取份数（1~3，默认 3）
 *   result: {...}
 * </pre>
 */
public final class JuicerLoader {

    private JuicerLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "juicer.yml");
        loadSettings(y);
        loadMix(y);
        int ok = 0, skip = 0;
        for (String key : y.getKeys(false)) {
            if (key.equalsIgnoreCase("mix")) continue; // 头部混合配置段，非配方
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue; // 全局设置等标量键
            try {
                if (register(key, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log("juicer.yml " + key + " 解析失败，跳过: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("juicer.yml: 注册 " + ok + ", 跳过 " + skip
                + (JuicerRecipe.mix != null ? ", 可混合物品 " + JuicerRecipe.mix.mixableRefs.size() : ""));
    }

    /** 解析头部全局设置：玩家名 lore 开关与文本格式、铁砧损耗开关与概率。 */
    private static void loadSettings(YamlConfiguration y) {
        JuicerRecipe.playerNames = y.getBoolean("player-names", true);
        JuicerRecipe.playerNamesFormat = y.getString("player-names-format", JuicerRecipe.playerNamesFormat);
        JuicerRecipe.anvilDamage = y.getBoolean("anvil-damage", false);
        JuicerRecipe.anvilDamageChance = Math.max(1, Math.min(10, y.getInt("anvil-damage-chance", 3)));
        JuicerRecipe.sugarAlcoholRatio = Math.max(0, y.getDouble("sugar-alcohol-ratio", 0.5));
        String growth = y.getString("aging-growth", "0.05~0.15");
        try {
            String[] parts = growth.split("~");
            double min = Double.parseDouble(parts[0].trim());
            double max = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : min;
            JuicerRecipe.agingGrowthMin = Math.max(0, Math.min(min, max));
            JuicerRecipe.agingGrowthMax = Math.max(min, max);
        } catch (NumberFormatException ex) {
            WT.log("juicer.yml aging-growth 格式错误（应为 min~max），使用默认值");
            JuicerRecipe.agingGrowthMin = 0.05;
            JuicerRecipe.agingGrowthMax = 0.15;
        }
        JuicerRecipe.wineLoreFormat = y.getString("wine-lore-format", JuicerRecipe.wineLoreFormat);
    }

    /** 解析头部 mix 段（可混合材料列表 + 榨汁进度 + 瓶/桶产物模板），放在配方注册之前。 */
    private static void loadMix(YamlConfiguration y) {
        ConfigurationSection s = y.getConfigurationSection("mix");
        if (s == null) {
            JuicerRecipe.mix = null;
            return;
        }
        JuicerRecipe.MixCfg cfg = new JuicerRecipe.MixCfg();
        // recipes 直接为材料 id 列表（材质名或粘液物品 id）
        for (String id : s.getStringList("recipes")) {
            if (id == null || id.trim().isEmpty()) continue;
            String v = id.trim();
            String ref;
            if (v.startsWith("sf:")) {
                ref = "sf:" + v.substring(3);
            } else {
                Material m = Material.matchMaterial(v);
                ref = m != null ? "mc:" + m.name() : "sf:" + v;
            }
            cfg.mixableRefs.add(ref);
        }
        cfg.progress = Math.max(1, s.getInt("progress", 10));
        ItemStack bottle = Read.item(s.getConfigurationSection("bottle"), false);
        if (bottle == null) {
            bottle = new ItemStack(Material.POTION);
            ItemMeta meta = bottle.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Colors.c("&f&l混合果汁"));
                bottle.setItemMeta(meta);
            }
        }
        ItemStack bucket = Read.item(s.getConfigurationSection("bucket"), false);
        if (bucket == null) {
            bucket = new ItemStack(Material.WATER_BUCKET);
            ItemMeta meta = bucket.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Colors.c("&f&l混合果汁桶"));
                bucket.setItemMeta(meta);
            }
        }
        cfg.bottleTemplate = bottle;
        cfg.bucketTemplate = bucket;
        JuicerRecipe.mix = cfg.mixableRefs.isEmpty() ? null : cfg;
    }

    private static boolean register(String key, ConfigurationSection s) {
        Map<String, Integer> ingredientRefs = parseIngredient(s, key);
        if (ingredientRefs == null) return false;
        if (sum(ingredientRefs) > 10) {
            WT.log("juicer.yml " + key + ": 投入总数超过盆容量 10，该配方无法投满，跳过");
            return false;
        }
        String firstRef = ingredientRefs.keySet().iterator().next();
        ItemStack ingredientDisplay = resolveDisplay(firstRef, key);
        if (ingredientDisplay == null) return false;

        // 压榨方式：支持逗号分隔多值「player,anvil」——踩踏与铁砧砸击均可榨汁
        boolean playerType = false;
        boolean anvilType = false;
        for (String t : s.getString("type", "player").toLowerCase(java.util.Locale.ROOT).split("[,，]")) {
            String v = t.trim();
            if (v.equals("player")) playerType = true;
            else if (v.equals("anvil")) anvilType = true;
        }
        if (!playerType && !anvilType) playerType = true; // 无有效值时回退玩家型
        int progress = Math.max(1, s.getInt("progress", 10));
        // 榨好后的接取份数（= 榨汁盆含水等级，1~3；瓶子每次 1 份，桶满时一次接完）
        int yield = Math.max(1, Math.min(3, s.getInt("yield", 3)));

        ItemStack result = Read.item(s.getConfigurationSection("result"), true);
        if (result == null) {
            WT.log("juicer.yml " + key + ": result 无效，跳过");
            return false;
        }

        JuicerRecipe recipe = new JuicerRecipe(key, ingredientRefs, ingredientDisplay,
                playerType, anvilType, progress, yield, result);
        // 可选 bucket 段：自定义桶装形态（缺省自动生成「桶装<产物名>」，lore 与瓶装一致）
        ItemStack bucketForm = Read.item(s.getConfigurationSection("bucket"), false);
        if (bucketForm != null) recipe.bucketForm = bucketForm;
        JuicerRecipe.register(recipe);
        return true;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    /**
     * 解析投入物多集：ingredient 支持「id:数量」字符串/列表（id 为材质名或粘液物品 id，
     * 数量缺省 1），兼容旧的单 id 字符串写法。全部无效返回 null。
     */
    private static Map<String, Integer> parseIngredient(ConfigurationSection s, String key) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        Object ing = s.get("ingredient");
        if (ing instanceof java.util.List) {
            for (Object o : (java.util.List<?>) ing) {
                if (o instanceof String) addRef(refs, (String) o, key);
            }
        } else if (ing instanceof String) {
            addRef(refs, (String) ing, key);
        } else if (s.isConfigurationSection("ingredient")) {
            // 兼容旧段写法 { material_type, material, amount }
            ConfigurationSection sec = s.getConfigurationSection("ingredient");
            String type = sec.getString("material_type", "mc");
            String material = sec.getString("material", "");
            int amount = Math.max(1, sec.getInt("amount", 1));
            String ref = "slimefun".equalsIgnoreCase(type) ? "sf:" + material : refOfMaterial(material, key);
            if (ref != null) refs.put(ref, amount);
        }
        if (refs.isEmpty()) {
            WT.log("juicer.yml " + key + ": 缺少有效 ingredient，跳过");
            return null;
        }
        return refs;
    }

    private static void addRef(Map<String, Integer> refs, String entry, String key) {
        String v = entry.trim();
        if (v.isEmpty()) return;
        int count = 1;
        int idx = v.lastIndexOf(':');
        if (idx > 0) {
            try {
                count = Integer.parseInt(v.substring(idx + 1).trim());
                v = v.substring(0, idx).trim();
            } catch (NumberFormatException ignored) {
                // 冒号后不是数字：整段视为 id（数量按 1）
            }
        }
        if (count < 1) count = 1;
        String ref;
        if (v.startsWith("sf:")) {
            ref = "sf:" + v.substring(3);
        } else {
            Material m = Material.matchMaterial(v);
            if (m != null) {
                ref = "mc:" + m.name();
            } else {
                ref = "sf:" + v; // 非原版材质按粘液物品 id 处理
            }
        }
        refs.merge(ref, count, Integer::sum);
    }

    private static String refOfMaterial(String material, String key) {
        Material m = Material.matchMaterial(material);
        if (m == null) {
            WT.log("juicer.yml " + key + ": 未知材质 " + material + "，跳过");
            return null;
        }
        return "mc:" + m.name();
    }

    /** 投入物展示堆：粘液物品取其注册物品（未注册则报错跳过），原版材质直接构建。 */
    private static ItemStack resolveDisplay(String ref, String key) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf == null) {
                WT.log("juicer.yml " + key + ": 引用的粘液物品 " + ref.substring(3) + " 未注册，跳过");
                return null;
            }
            return sf.getItem().clone();
        }
        Material m = Material.matchMaterial(ref.substring(3));
        return new ItemStack(m != null ? m : Material.BARRIER);
    }
}
