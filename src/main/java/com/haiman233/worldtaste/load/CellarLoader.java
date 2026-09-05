package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.CellarRecipe;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载 cellar.yml 酒窖配方（须在 items/foods 物品注册后调用）。
 *
 * <pre>
 * options:
 *   cellar-name: true   # 酒窖命名功能开关
 * KEY:
 *   ingredient:
 *     - APPLE:2          # 材料id:数量（id 为材质名或粘液物品 id；数量 = 该原料的总份数，
 *                        # 即榨汁盆配方的材料写法，如 1 单位甜浆果汁 = 3 份甜浆果）
 *     - SWEET_BERRIES:1
 *   aging: true          # 是否允许陈酿（true=产物含酒精可陈化；false=副产物无酒精度）
 *   result:              # 每单位产物（item 段同构）
 *     material: POTION
 *     name: '&6&l水果酒'
 *     amount: 1
 * </pre>
 */
public final class CellarLoader {

    private CellarLoader() {}

    /** 酒窖命名功能开关（cellar.yml 的 options.cellar-name，缺省开启）。 */
    public static boolean cellarNameEnabled = true;

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "cellar.yml");
        cellarNameEnabled = y.getBoolean("options.cellar-name", true);
        int ok = 0, skip = 0;
        for (String key : y.getKeys(false)) {
            if (key.equalsIgnoreCase("options")) continue;
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            try {
                if (register(key, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log("cellar.yml " + key + " 解析失败，跳过: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("cellar.yml: 注册 " + ok + ", 跳过 " + skip);
    }

    private static boolean register(String key, ConfigurationSection s) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        for (String entry : s.getStringList("ingredient")) {
            String v = entry == null ? "" : entry.trim();
            if (v.isEmpty()) continue;
            int count = 1;
            int idx = v.lastIndexOf(':');
            if (idx > 0) {
                try {
                    count = Integer.parseInt(v.substring(idx + 1).trim());
                    v = v.substring(0, idx).trim();
                } catch (NumberFormatException ignored) {
                    // 冒号后非数字：整段视为 id
                }
            }
            if (count < 1) count = 1;
            String ref;
            if (v.startsWith("mc:") || v.startsWith("sf:")) {
                ref = v;
            } else {
                Material m = Material.matchMaterial(v);
                ref = m != null ? "mc:" + m.name() : "sf:" + v;
            }
            refs.merge(ref, count, Integer::sum);
        }
        if (refs.isEmpty()) {
            WT.log("cellar.yml " + key + ": 缺少有效 ingredient，跳过");
            return false;
        }
        boolean aging = s.getBoolean("aging", true);
        ItemStack result = Read.item(s.getConfigurationSection("result"), true);
        if (result == null) {
            WT.log("cellar.yml " + key + ": result 无效，跳过");
            return false;
        }
        CellarRecipe.register(new CellarRecipe(key, refs, aging, result));
        return true;
    }
}
