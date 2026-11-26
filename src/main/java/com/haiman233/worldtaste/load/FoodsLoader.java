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
 * 加载 foods.yml：应用 FoodComponent(canAlwaysEat 恒 true) 使食物可食，
 * 并登记 onEat 脚本(kind=eat)供 {@link com.haiman233.worldtaste.behavior.FoodConsumeListener} 追加效果。
 * eatseconds：Paper 1.21.11 的 FoodComponent 不支持，忽略（与 RSC 一致）。
 *
 * <p>组件数值来源（与 ItemsLoader 语义对齐）：脚本 opts（简单数值型，即
 * {@code componentCarriesFood()}）的 food/saturation <b>优先</b>写进 FoodComponent
 * （如动物奶的 milk 脚本 3/3）；无脚本或脚本非数值型时回落 foods.yml 自身的
 * nutrition/saturation 键（缺省 0 → &lt;1 提升为 1，对齐 RSC FoodReader）。
 * 原版进食模式（food.native-eat）下经 {@link FoodHelper#applyNativeFood} 一并注入
 * consumable 组件（动画/音效/药水）。</p>
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
                //
                // 组件数值来源（方案 A，与 ItemsLoader 对齐）：脚本 opts 值优先——
                // 简单数值型脚本（componentCarriesFood：有 food 且无 foodSet/randomFood）的
                // food/saturation 直接写进 FoodComponent（如动物奶的 milk 脚本 3/3）；
                // 运行期 FoodConsumeListener 按 componentCarriesFood()=true 跳过脚本结算，
                // 组件不承载真实数值会导致恢复量坍缩为 bump 后的 1/0.0。
                // 无脚本或脚本非数值型时回落 foods.yml 自身的 nutrition/saturation 键
                // （缺省 0 → <1 提升为 1，对齐 RSC FoodReader 语义）。
                String script0 = s.getString("script");
                com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts opts0 = script0 != null
                        ? com.haiman233.worldtaste.behavior.Behaviors.consumables.get(script0.trim()) : null;
                boolean scriptCarriesFood = opts0 != null && opts0.componentCarriesFood();
                int nutrition = scriptCarriesFood ? Math.max(0, opts0.food.intValue()) : s.getInt("nutrition", 0);
                float saturation = scriptCarriesFood
                        ? (opts0.saturation != null ? opts0.saturation.floatValue() : 0f)
                        : (float) s.getDouble("saturation", 0);
                if (nutrition < 1) nutrition = 1;
                if (saturation < 0f) saturation = 0f;
                // 原版进食模式：applyNativeFood 一并注入 consumable 组件（动画/音效/药水），
                // 覆盖 Paper 自动补的默认——饮品（POTION 材质或脚本音效为
                // entity.generic.drink）走原版 DRINK 动画 + 饮用音效；
                // 可选 consume-animation 字段显式指定（eat/drink），优先级最高。
                if (WTConfig.nativeEat) {
                    String anim = s.getString("consume-animation",
                            display.getType() == org.bukkit.Material.POTION ? "drink" : "eat");
                    String skey = opts0 != null ? (opts0.silent ? "intentionally_empty" : opts0.soundKey) : null;
                    if ("drink".equals(anim) && skey == null) skey = "entity.generic.drink";
                    java.util.List<org.bukkit.potion.PotionEffect> fx = opts0 != null
                            ? FoodHelper.potionEffects(opts0.potions) : null;
                    FoodHelper.applyNativeFood(display, true, anim, skey, eatSeconds, nutrition, saturation, fx);
                    // 药水已写入组件（1.21.2+ 原版进食自动施加）→ 标记防脚本双重结算
                    // （与 ItemsLoader 一致；legacy/noop 段 fx 为空列表写入、标记不置位，脚本照旧结算）
                    if (opts0 != null && fx != null && !fx.isEmpty()
                            && com.haiman233.worldtaste.compat.Compat.hasConsumeEffectsApi()) {
                        opts0.componentCarriesEffects = true;
                    }
                } else {
                    // 旧脚本模式：只写 FoodComponent（可食），效果仍由 FoodConsumeListener 脚本结算
                    boolean foodOk = FoodHelper.apply(display, nutrition, saturation, true, eatSeconds, false);
                    if (!foodOk) foodFail++;
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
