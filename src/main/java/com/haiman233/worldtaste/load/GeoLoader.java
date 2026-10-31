package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.items.WTGeoResource;
import com.haiman233.worldtaste.util.Colors;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import com.haiman233.worldtaste.util.Lang;

/**
 * 加载 geo_resources.yml：以普通物品注册（指南可见、可被配方引用），并注册为真正的 {@link WTGeoResource}，
 * 使 GEO 采掘机能按 supply 产出、GEO 扫描仪能探测。
 */
public final class GeoLoader {

    private GeoLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "geo_resources.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                if (!ItemsLoader.register(id, s)) continue;
                ItemStack display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
                if (display == null) continue;
                String name = Colors.c(s.getString("geo_name", id));
                int maxDev = s.getInt("max_deviation", 0);
                boolean obtain = s.getBoolean("obtain_from_geo_miner", true);
                ConfigurationSection sup = s.getConfigurationSection("supply");
                int sn = sup == null ? 0 : sup.getInt("normal", 0);
                int snether = sup == null ? 0 : sup.getInt("nether", 0);
                int send = sup == null ? 0 : sup.getInt("the_end", 0);
                WTGeoResource res = new WTGeoResource(
                        new NamespacedKey(WT.plugin, id.toLowerCase(java.util.Locale.ROOT)),
                        display, name, maxDev, obtain, sn, snether, send);
                res.register();
                ok++;
            } catch (Exception e) {
                WT.log("geo_resources.yml " + id + Lang.get("log.geoloader.001") + e);
            }
        }
        WT.plugin.getLogger().info(Lang.get("log.geoloader.002") + ok);
    }
}
