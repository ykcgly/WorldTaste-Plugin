package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.MenuDef;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTWorkbench;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加载 workbenches.yml → {@link WTWorkbench}（点击合成）。 */
public final class WorkbenchLoader {

    private WorkbenchLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "workbenches.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { WT.log(id + ": 物品组缺失"); skip++; continue; }
                if (WTConfig.blockItem(s.getString("item_group"), id)) { skip++; continue; }
                ItemStack display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { WT.log(id + ": 无展示物品"); skip++; continue; }
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] craftRecipe = Read.recipe(s.getConfigurationSection("recipe"), 9);
                int[] input = RecipeMachineLoader.intList(s, "input");
                int[] output = RecipeMachineLoader.intList(s, "output");
                if (input.length == 0) input = new int[] { 10 };
                if (output.length == 0) output = new int[] { 16 };
                int capacity = s.getInt("capacity", 128);
                int energyPerCraft = s.getInt("energyPerCraft", 8);
                int click = s.getInt("click", 22);
                boolean hideAll = s.getBoolean("hideAllRecipes", false);
                List<WTRecipe> recipes = RecipeMachineLoader.readRecipes(s.getConfigurationSection("recipes"), input.length);
                MenuDef menu = WT.menus.get(id);
                WTWorkbench w = new WTWorkbench(g, sfis, rt, craftRecipe, input, output, recipes,
                        capacity, energyPerCraft, 1, menu, hideAll, click);
                w.register(WT.plugin);
                ok++;
            } catch (Exception e) {
                WT.log("workbenches.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("workbenches.yml: 注册 " + ok + ", 跳过 " + skip);
    }
}
