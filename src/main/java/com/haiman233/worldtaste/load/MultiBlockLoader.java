package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.WTMultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import com.haiman233.worldtaste.util.Lang;

/** 加载 mb_machines.yml → {@link WTMultiBlockMachine}。 */
public final class MultiBlockLoader {

    private MultiBlockLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "mb_machines.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { MissingItems.record(Lang.get("msg.multiblockloader.001") + s.getString("item_group")); skip++; continue; }
                if (WTConfig.blockItem(s.getString("item_group"), id)) { skip++; continue; }
                ItemStack display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { MissingItems.record(Lang.get("msg.multiblockloader.002") + id); skip++; continue; }
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] structure = Read.recipe(s.getConfigurationSection("recipe"), 9);
                int work = s.getInt("work", 5);
                // 校验 work 槽位：越界或该槽未填方块会在右键时抛 AIOOBE/NPE
                if (work < 1 || work > 9 || structure[work - 1] == null) {
                    WT.log(id + Lang.get("log.multiblockloader.001") + work + Lang.get("log.multiblockloader.002"));
                    skip++;
                    continue;
                }
                SoundEffect sound = parseSound(s.getString("sound"));
                Map<ItemStack[], ItemStack> recipes = readMbRecipes(s.getConfigurationSection("recipes"));
                WTMultiBlockMachine m = new WTMultiBlockMachine(g, sfis, structure, recipes, work, sound);
                m.register(WT.plugin);
                ok++;
            } catch (Exception e) {
                WT.log("mb_machines.yml " + id + Lang.get("log.multiblockloader.003") + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(Lang.get("log.multiblockloader.004") + ok + Lang.get("log.multiblockloader.005") + skip);
    }

    static Map<ItemStack[], ItemStack> readMbRecipes(ConfigurationSection recipesSec) {
        Map<ItemStack[], ItemStack> out = new LinkedHashMap<>();
        if (recipesSec == null) return out;
        for (String name : recipesSec.getKeys(false)) {
            ConfigurationSection r = recipesSec.getConfigurationSection(name);
            if (r == null) continue;
            try {
                ItemStack[] input = Read.recipe(r.getConfigurationSection("input"), 9); // 与发射器 9 槽对齐
                ItemStack output = Read.item(r.getConfigurationSection("output"), true);
                if (output != null) out.put(input, output);
            } catch (Exception e) {
                WT.log(Lang.get("log.multiblockloader.006") + name + Lang.get("log.multiblockloader.007") + e);
            }
        }
        return out;
    }

    private static SoundEffect parseSound(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return SoundEffect.valueOf(name.trim());
        } catch (Exception e) {
            WT.log(Lang.get("log.multiblockloader.008") + name + Lang.get("log.multiblockloader.009"));
            return null;
        }
    }
}
