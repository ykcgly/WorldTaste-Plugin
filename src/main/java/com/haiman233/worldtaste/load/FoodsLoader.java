package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.items.ItemSpec;
import com.haiman233.worldtaste.items.ScriptItemFactory;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import com.haiman233.worldtaste.util.Lang;

/**
 * 加载 foods.yml：应用 FoodComponent(nutrition/saturation/canAlwaysEat 恒 true) 使食物可食，
 * 并登记 onEat 脚本(kind=eat)供 {@link com.haiman233.worldtaste.behavior.FoodConsumeListener} 追加效果。
 * eatseconds：Paper 1.21.11 的 FoodComponent 不支持，忽略（与 RSC 一致）。
 *
 * <p>饱食度检测已整体移除： foods.yml 全部食物饱食度满时（含创造模式恒 20）同样可食用，
 * {@code canAlwaysEat} 恒为 true，数据里的 {@code always_eatable} 字段不再读取。</p>
 */
public final class FoodsLoader {

    private FoodsLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "foods.yml");
        int ok = 0, skip = 0, foodFail = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                if (!RegisterConditions.pass(s)) { skip++; continue; }
                String effId = s.getString("id_alias", id);
                ItemGroup g = WT.group(s.getString("item_group"));
                if (g == null) { MissingItems.record(Lang.get("msg.foodsloader.001") + s.getString("item_group")); skip++; continue; }
                ItemStack display = WT.preload.get(effId.toUpperCase(java.util.Locale.ROOT));
                if (display == null) { MissingItems.record(Lang.get("msg.foodsloader.002") + effId); skip++; continue; }
                display = display.clone();
                float eatSeconds = (float) s.getDouble("eatseconds", s.getDouble("eat_seconds", 0));
                // canAlwaysEat 恒 true：饱食度检测已整体移除（饱食度满/创造模式同样可食用），
                // 与 items.yml 脚本食物路径语义一致；数据里的 always_eatable 字段已废弃
                boolean foodOk = FoodHelper.apply(display, s.getInt("nutrition", 0), (float) s.getDouble("saturation", 0),
                        true, eatSeconds);
                if (!foodOk) foodFail++;
                // 原版进食模式：显式注入 consumable 组件，覆盖 Paper 自动补的默认（保证动画/音效正确）——
                // 饮品（POTION 材质或脚本音效为 entity.generic.drink）走原版 DRINK 动画 + 饮用音效；
                // 可选 consume-animation 字段显式指定（eat/drink），优先级最高。
                if (WTConfig.nativeEat) {
                    String anim = s.getString("consume-animation",
                            display.getType() == org.bukkit.Material.POTION ? "drink" : "eat");
                    String script0 = s.getString("script");
                    com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts opts0 = script0 != null
                            ? com.haiman233.worldtaste.behavior.Behaviors.consumables.get(script0.trim()) : null;
                    String skey = opts0 != null ? (opts0.silent ? "intentionally_empty" : opts0.soundKey) : null;
                    if ("drink".equals(anim) && skey == null) skey = "entity.generic.drink";
                    FoodHelper.applyConsumable(display, eatSeconds, anim, skey);
                }

                SlimefunItemStack sfis = new SlimefunItemStack(effId, display);
                RecipeType rt = RecipeTypes.resolve(s.getString("recipe_type", "NULL"));
                ItemStack[] recipe = Read.recipe(s.getConfigurationSection("recipe"), 9);
                ItemSpec spec = ItemSpec.from(effId, s);
                SlimefunItem item = ScriptItemFactory.create(spec, g, sfis, rt, recipe);
                if (spec.vanilla) { try { item.setUseableInWorkbench(true); } catch (Throwable ignored) {} }
                item.register(WT.plugin);

                String script = s.getString("script");
                if (script != null) {
                    ConsumableOpts opts = Behaviors.consumables.get(script.trim());
                    if (opts != null && !opts.use) Behaviors.foodOnEat.put(effId, opts);
                }
                ok++;
            } catch (Exception e) {
                WT.log("foods.yml " + id + Lang.get("log.foodsloader.001") + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info(Lang.get("log.foodsloader.002") + ok + Lang.get("log.foodsloader.003") + skip);
        if (foodFail > 0) {
            WT.plugin.getLogger().severe("foods.yml: " + foodFail
                    + Lang.get("msg.foodsloader.003"));
        }
    }
}
