package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.MenuDef;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加载 recipe_machines.yml → {@link WTRecipeMachine}。 */
public final class RecipeMachineLoader {

    private RecipeMachineLoader() {}

    public static void load() {
        // 电力配方机器：recipe_machines / linked_recipe_machines（workbench 由 WorkbenchLoader 单独处理）
        load("recipe_machines.yml");
        load("linked_recipe_machines.yml");
    }

    public static void load(String file) {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, file);
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { WT.log(id + ": 物品组缺失"); skip++; continue; }
                ItemStack display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { WT.log(id + ": 无展示物品"); skip++; continue; }
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] craftRecipe = Read.recipe(s.getConfigurationSection("recipe"), 9);
                int[] input = intList(s, "input");
                int[] output = intList(s, "output");
                if (input.length == 0) input = new int[] { 10 };
                if (output.length == 0) output = new int[] { 16 };
                int capacity = s.getInt("capacity", 128);
                int energyPerCraft = s.getInt("energyPerCraft", 8);
                int speed = s.getInt("speed", 1);
                boolean hideAll = s.getBoolean("hideAllRecipes", false);
                List<WTRecipe> recipes = readRecipes(s.getConfigurationSection("recipes"), input.length);
                MenuDef menu = WT.menus.get(id);
                WTRecipeMachine m = new WTRecipeMachine(g, sfis, rt, craftRecipe, input, output, recipes,
                        capacity, energyPerCraft, speed, menu, hideAll);
                m.register(WT.plugin);
                ok++;
            } catch (Exception e) {
                WT.log(file + " " + id + " 注册失败: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(file + ": 注册 " + ok + ", 跳过 " + skip);
    }

    /** 读取 recipe_machines 的 recipes 段。 */
    public static List<WTRecipe> readRecipes(ConfigurationSection recipesSec, int defaultInputSize) {
        List<WTRecipe> out = new ArrayList<>();
        if (recipesSec == null) return out;
        for (String name : recipesSec.getKeys(false)) {
            ConfigurationSection r = recipesSec.getConfigurationSection(name);
            if (r == null) continue;
            try {
                int seconds = r.getInt("seconds", 1);
                ConfigurationSection inSec = r.getConfigurationSection("input");
                List<ItemStack> inList = new ArrayList<>();
                List<Integer> inSlotList = new ArrayList<>();
                List<Boolean> ncList = new ArrayList<>();
                List<Integer> dmgList = new ArrayList<>();
                boolean noConsumeAll = r.getBoolean("noConsume", false);
                if (inSec != null) {
                    for (String k : inSec.getKeys(false)) {
                        ConfigurationSection is = inSec.getConfigurationSection(k);
                        if (is == null) continue;
                        ItemStack it = Read.item(is, true);
                        if (it == null) continue;
                        inList.add(it);
                        inSlotList.add(is.getInt("slot", -1));
                        ncList.add(noConsumeAll || is.getBoolean("noConsume", false));
                        // damage: 工具类输入（如钓竿）每次合成消耗的耐久点数，缺省 0=整件消耗
                        dmgList.add(is.getInt("damage", 0));
                    }
                }
                ItemStack[] input = inList.toArray(new ItemStack[0]);
                int[] inSlots = inSlotList.stream().mapToInt(Integer::intValue).toArray();
                int[] inputDamage = dmgList.stream().mapToInt(Integer::intValue).toArray();
                boolean[] noConsume = new boolean[ncList.size()];
                for (int i = 0; i < ncList.size(); i++) noConsume[i] = ncList.get(i);

                List<ItemStack> outs = new ArrayList<>();
                List<Integer> chances = new ArrayList<>();
                List<Integer> outSlotList = new ArrayList<>();
                ConfigurationSection outSec = r.getConfigurationSection("output");
                if (outSec != null) {
                    for (String k : outSec.getKeys(false)) {
                        ConfigurationSection o = outSec.getConfigurationSection(k);
                        if (o == null) continue;
                        ItemStack it = Read.item(o, true);
                        if (it == null) continue;
                        outs.add(it);
                        chances.add(o.getInt("chance", 100));
                        outSlotList.add(o.getInt("slot", -1));
                    }
                }
                int[] outSlots = outSlotList.stream().mapToInt(Integer::intValue).toArray();
                boolean chooseOne = r.getBoolean("chooseOne", false);
                int[] ch = chances.stream().mapToInt(Integer::intValue).toArray();
                // CraftingOperation 校验 input/output 非空，空配方会令 tick 抛异常，跳过。
                if (input.length == 0 || outs.isEmpty()) {
                    WT.log("配方 " + name + " 输入或输出为空，跳过");
                    continue;
                }
                out.add(new WTRecipe(seconds, input, outs.toArray(new ItemStack[0]), ch, chooseOne, noConsume, inSlots, outSlots, inputDamage));
            } catch (Exception e) {
                WT.log("配方 " + name + " 解析失败: " + e);
            }
        }
        return out;
    }

    public static int[] intList(ConfigurationSection s, String key) {
        List<Integer> list = s.getIntegerList(key);
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
