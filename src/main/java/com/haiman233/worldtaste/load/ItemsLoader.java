package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.BlockDrops;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.items.ItemSpec;
import com.haiman233.worldtaste.items.ScriptItemFactory;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * 加载物品类配置。items.yml（消耗品/食材/装饰）与 machines.yml（脚本驱动的可放置物品，多为作物）结构一致，
 * 统一经 {@link ScriptItemFactory} 按脚本+属性分派物品子类。支持 register.conditions、lateInit(两遍)、id_alias、
 * placeable、drop_from、vanilla、radiation/soulbound/anti_wither/piglin/energy 等属性。
 */
public final class ItemsLoader {

    private ItemsLoader() {}

    public static void load() {
        loadFile("items.yml");
        loadFile("machines.yml");
    }

    public static void loadFile(String file) {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, file);
        int ok = 0, skip = 0;
        List<ConfigurationSection> late = new ArrayList<>();
        List<String> lateIds = new ArrayList<>();
        // 第一遍：非 lateInit
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getBoolean("lateInit", false)) {
                late.add(s);
                lateIds.add(id);
                continue;
            }
            try {
                if (register(id, s)) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log(file + " " + id + " 注册失败: " + e);
                skip++;
            }
        }
        // 第二遍：lateInit
        for (int i = 0; i < late.size(); i++) {
            String id = lateIds.get(i);
            try {
                if (register(id, late.get(i))) ok++;
                else skip++;
            } catch (Exception e) {
                WT.log(file + " " + id + "(lateInit) 注册失败: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(file + ": 注册 " + ok + ", 跳过 " + skip);
    }

    /** 通用物品注册（items/machines/foods/mob_drops/geo 复用）。成功返回 true。 */
    static boolean register(String id, ConfigurationSection s) {
        // 注册条件
        if (!RegisterConditions.pass(s)) return false;

        // 占位玻璃板物品（各分组分割板：材质 *_STAINED_GLASS_PANE 且无配方）：
        // 不注册为 Slimefun 物品，不再出现在指南中；旧存档遗留的此类物品因 id 未注册而自然失效
        ConfigurationSection itemSec = s.getConfigurationSection("item");
        if (itemSec != null) {
            String mat = itemSec.getString("material", "");
            String rt = s.getString("recipe_type", "NULL");
            if (mat.endsWith("_STAINED_GLASS_PANE") && ("NULL".equalsIgnoreCase(rt) || rt.isEmpty())) {
                return false;
            }
        }

        String effId = s.getString("id_alias", id);
        ItemGroup g = WT.group(s.getString("item_group"));
        if (g == null) {
            WT.log(effId + ": 物品组 " + s.getString("item_group") + " 缺失，跳过");
            return false;
        }
        // 装饰分隔板组（groups.yml type: button，如 ws_zwf_*）内挂载的占位物品
        //（PAPER「这就是一个占位符而已」）一律不注册——任何指南里都不出现
        if (g instanceof DecorativeSubGroup) {
            return false;
        }
        ItemStack display = WT.preload.get(effId.toUpperCase(java.util.Locale.ROOT));
        if (display == null) display = WT.preload.get(id.toUpperCase(java.util.Locale.ROOT));
        if (display == null) {
            WT.log(effId + ": 无展示物品，跳过");
            return false;
        }

        ItemSpec spec = ItemSpec.from(effId, s);
        // 酒类饮品（items.yml alcohol 字段）：展示 lore 追加酒精度并登记联动数值。
        // 修改前先 clone，避免污染 WT.preload 中的共享展示堆（配方引用仍用原堆）。
        if (spec.alcohol > 0) {
            display = display.clone();
            org.bukkit.inventory.meta.ItemMeta meta = display.getItemMeta();
            java.util.List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add(Colors.c("&7▷▷ &b酒精度: &e" + spec.alcohol));
            meta.setLore(lore);
            display.setItemMeta(meta);
            ExoticGardenHook.register(effId, spec.alcohol);
        }

        SlimefunItemStack sfis = new SlimefunItemStack(effId, display);
        RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
        ItemStack[] recipe = Read.recipe(s.getConfigurationSection("recipe"), 9);

        SlimefunItem item = ScriptItemFactory.create(spec, g, sfis, rt, recipe);

        if (spec.vanilla) {
            try { item.setUseableInWorkbench(true); } catch (Throwable ignored) {}
        }
        item.register(WT.plugin);


        // 方块破坏掉落
        if (spec.dropFrom != null) {
            Material block = Material.matchMaterial(spec.dropFrom);
            int[] range = parseAmountRange(s.getString("drop_amount", "1"));
            BlockDrops.add(block, effId, spec.dropChance, range[0], range[1]);
        }

        return true;
    }

    /** 解析 drop_amount（支持 "1" 或 "1-3" 区间），返回 {min,max}，由 BlockDrops 每次掉落时掷。 */
    private static int[] parseAmountRange(String value) {
        if (value != null) {
            int dash = value.indexOf('-');
            if (dash > 0) {
                try {
                    int lo = Integer.parseInt(value.substring(0, dash).trim());
                    int hi = Integer.parseInt(value.substring(dash + 1).trim());
                    if (lo >= 1 && hi >= lo) return new int[]{lo, hi};
                } catch (NumberFormatException ignored) { }
            }
            try {
                int v = Integer.parseInt(value.trim());
                if (v >= 1) return new int[]{v, v};
            } catch (NumberFormatException ignored) { }
        }
        return new int[]{1, 1};
    }
}