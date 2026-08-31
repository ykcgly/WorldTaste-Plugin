package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.JuicerRecipe;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 加载 sugar.yml 糖分值配置（材料 id → 糖分）。
 *
 * <p>id 写法同 juicer.yml 的 ingredient（材质名或粘液物品 id，亦接受 mc:/sf: 前缀）。
 * 产物总糖分 = Σ 材料糖分 × 投入数量；未列出的材料按 0 计。糖分数据经产物 PDC
 * （juice_sugar）与盆状态（BlockStorage）持久化，供后续玩法功能读取。</p>
 */
public final class SugarLoader {

    private SugarLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "sugar.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            if (!y.isInt(id)) continue; // 顶行注释说明自动被忽略，非数字值跳过
            int sugar = Math.max(0, y.getInt(id));
            JuicerRecipe.setSugar(resolveRef(id.trim()), sugar);
            ok++;
        }
        WT.plugin.getLogger().info("sugar.yml: 注册 " + ok + " 种材料的糖分值");
    }

    /** 材料 id → 内容物 ref（mc:/sf: 前缀）。 */
    private static String resolveRef(String id) {
        if (id.startsWith("mc:") || id.startsWith("sf:")) return id;
        Material m = Material.matchMaterial(id);
        return m != null ? "mc:" + m.name() : "sf:" + id;
    }
}
