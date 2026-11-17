package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.MenuDef;
import com.haiman233.worldtaste.machines.WTPackMachine;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import com.haiman233.worldtaste.util.Lang;

/**
 * 打包台（pack_machines.yml → {@link WTPackMachine}：头颅食物打包为放置形态，
 * 不耗电不 tick，点击「打包」按钮即时转换；拆包由手持蹲下右键空气完成）。
 *
 * <p>加载时机约束：必须在 {@code Setup.loadAll} 内、{@code WT.preload.clear()}（R9）之前
 * 调用。放置形态模板不再由本 loader 固化——可打包集合为「全附属头颅模型 + food 组件」物品，
 * 由 {@link WTPackMachine#freezePlaceableTemplates()} 在全部内容注册完毕后统一扫描固化。</p>
 */
public final class PackMachineLoader {

    private PackMachineLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "pack_machines.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                String effId = id.toUpperCase(Locale.ROOT);
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { MissingItems.record(Lang.get("msg.packmachineloader.001") + s.getString("item_group")); skip++; continue; }
                if (WTConfig.blockItem(s.getString("item_group"), id)) { skip++; continue; }
                ItemStack display = WT.preload.get(effId);
                if (display == null) { MissingItems.record(Lang.get("msg.packmachineloader.002") + id); skip++; continue; }
                SlimefunItemStack sfis = new SlimefunItemStack(id, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] craftRecipe = Read.recipe(s.getConfigurationSection("recipe"), 9);
                int convert = s.getInt("input", 10);
                int bag = s.getInt("bag", 13);
                int output = s.getInt("output", 16);
                int click = s.getInt("click", 22);
                String bagId = s.getString("bagId", "WT_DABAODAI");
                MenuDef menu = WT.menus.get(id);
                WTPackMachine m = new WTPackMachine(g, sfis, rt, craftRecipe,
                        convert, bag, output, click, bagId, menu);
                m.register(WT.plugin);
                ok++;
            } catch (Exception e) {
                WT.log("pack_machines.yml " + id + Lang.get("log.packmachineloader.001") + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(Lang.get("log.packmachineloader.002") + ok
                + Lang.get("log.packmachineloader.003") + skip);
        // 拆包监听：手持已打包（放置形态）物品蹲下右键空气 → 转回食用形态，不返还打包袋
        WTPackMachine.register();
    }
}
