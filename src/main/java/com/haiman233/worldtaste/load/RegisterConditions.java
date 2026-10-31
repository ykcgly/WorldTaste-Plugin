package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import com.haiman233.worldtaste.util.Lang;

/**
 * 评估物品段的 register.conditions（对齐 RSC YamlReader.checkForRegistration）。
 * 支持：hasplugin/!hasplugin、itemexist/!itemexist、version op x.y、config.*（忽略，视为通过）。
 */
public final class RegisterConditions {

    private RegisterConditions() {}

    public static boolean pass(ConfigurationSection section) {
        ConfigurationSection reg = section.getConfigurationSection("register");
        if (reg == null) return true;
        if (reg.getBoolean("unfinished", false)) return false;
        List<String> conditions = reg.getStringList("conditions");
        for (String raw : conditions) {
            String c = raw.trim();
            if (c.isEmpty()) continue;
            if (!eval(c)) {
                if (reg.getBoolean("warn", false)) WT.log(Lang.get("log.registerconditions.001") + c);
                return false;
            }
        }
        return true;
    }

    private static boolean eval(String c) {
        if (c.startsWith("!")) return !eval(c.substring(1).trim());
        if (c.startsWith("hasplugin ")) {
            return Bukkit.getPluginManager().getPlugin(c.substring("hasplugin ".length()).trim()) != null;
        }
        if (c.startsWith("itemexist ")) {
            String id = c.substring("itemexist ".length()).trim().toUpperCase();
            return SlimefunItem.getById(id) != null || WT.preload.containsKey(id);
        }
        if (c.startsWith("version ")) {
            // version <op> <x.y>
            String[] parts = c.substring("version ".length()).split("\\s+");
            if (parts.length == 2) return compareVersion(parts[0], parts[1]);
            return true;
        }
        if (c.startsWith("config.")) return true; // 依赖附属自定义配置，视为通过
        return true;
    }

    private static boolean compareVersion(String op, String target) {
        try {
            String[] cur = Bukkit.getMinecraftVersion().split("\\.");
            String[] t = target.split("\\.");
            int cmaj = Integer.parseInt(cur[0]);
            int cmin = cur.length > 1 ? Integer.parseInt(cur[1]) : 0;
            int cpatch = cur.length > 2 ? Integer.parseInt(cur[2]) : 0;
            int tmaj = Integer.parseInt(t[0]);
            int tmin = t.length > 1 ? Integer.parseInt(t[1]) : 0;
            int tpatch = t.length > 2 ? Integer.parseInt(t[2]) : 0;
            int cmp = Integer.compare(cmaj, tmaj);
            if (cmp == 0) cmp = Integer.compare(cmin, tmin);
            if (cmp == 0) cmp = Integer.compare(cpatch, tpatch);
            switch (op) {
                case ">": return cmp > 0;
                case "<": return cmp < 0;
                case ">=": return cmp >= 0;
                case "<=": return cmp <= 0;
                case "==": return cmp == 0;
                case "!=": return cmp != 0;
                default: return true;
            }
        } catch (Exception e) {
            return true;
        }
    }
}
