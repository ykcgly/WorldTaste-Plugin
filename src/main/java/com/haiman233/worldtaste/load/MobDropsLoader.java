package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 加载 mob_drops.yml：以普通物品注册，并记录 entity/chance 供生物死亡掉落监听器使用。
 *
 * <p>指南展示：配方类型为自定义的 {@link #mobDropType()}（worldtaste:mob_drop，图标为刷怪蛋
 * 「生物掉落」），配方中央放置该生物的刷怪蛋（lore「击杀 XX 时会有 N% 的概率掉落」，生物名走
 * translatable 由客户端按语言渲染），玩家在指南里即可看到产物由哪种生物掉落。entity/chance
 * 不是标准 recipe/recipe_type 字段结构，且 R6 缓存的配置只读（不得向共享 section 写入），故
 * 展示经 {@link ItemsLoader#register(String, ConfigurationSection, RecipeType, ItemStack[])}
 * 的覆盖参数注入。</p>
 *
 * <p><b>为什么不用 Slimefun 内置 {@code RecipeType.MOB_DROP}</b>：本服 Slimefun fork 在
 * {@code RecipeType.register} 时对 MOB_DROP 实例走 {@code registerMobDrop}——取配方第 5 格
 * 物品的<b>显示名</b>经 {@code EntityType.valueOf} 解析实体并注册进原生掉落表，显示名不是
 * 实体枚举名（如我们的蛋无显示名/中文名）会直接抛 IllegalArgumentException 导致物品加载
 * 失败；即使解析成功也会走 fork 原生掉落，与本插件的 MobDropListener 重复掉落。因此用
 * 自定义类型仅做展示，掉落仍由 {@code MobDropListener} 按 chance 处理。</p>
 *
 * <p>无刷怪蛋材质的生物（GIANT/PLAYER/MANNEQUIN 等）按 RSC 回退为 EGG 图标；entity: MARKER
 * 为旧版本的占位条目（真实生物存在时经 register.conditions 版本条件互斥替换；MARKER 实体
 * 永不触发死亡事件，实际不掉落），不注入掉落展示，保持无配方显示。</p>
 */
public final class MobDropsLoader {

    private MobDropsLoader() {}

    /** 按实体类型(大写)索引的掉落表：onDeath 直接按类型查表，避免线性扫描全部掉落(刷怪塔高频死亡场景)。 */
    public static final Map<String, List<Drop>> drops = new HashMap<>();

    /** 自定义掉落展示配方类型（懒加载：构造需 WT.plugin 已就绪）。 */
    private static RecipeType mobDropType;

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "mob_drops.yml");
        int ok = 0, skip = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                String entity = s.getString("entity");
                int chance = s.getInt("chance", 0);
                // 注册用的是 id_alias（effId），记录时也要用同一个 id，否则监听器 getById 查不到
                String effId = s.getString("id_alias", id);

                // 指南展示覆盖：自定义掉落类型 + 居中刷怪蛋（对齐 RSC 视觉；MARKER 占位不展示）
                EntityType type = parseEntityType(entity);
                RecipeType rtOverride = null;
                ItemStack[] recipeOverride = null;
                if (type != null && type != EntityType.MARKER) {
                    ItemStack egg = dropSourceEgg(type, chance);
                    if (egg != null) {
                        rtOverride = mobDropType();
                        recipeOverride = new ItemStack[] {null, null, null, null, egg, null, null, null, null};
                    }
                }

                if (!ItemsLoader.register(id, s, rtOverride, recipeOverride)) {
                    skip++;
                    continue;
                }

                if (entity != null && chance > 0) {
                    // 掉落表键统一取 EntityType#name()（当前运行时枚举名）：修复 yml 旧枚举名
                    // （MUSHROOM_COW/SNOWMAN）在 1.20.5+ 服务端与 EntityType#name() 永不相等的
                    // 死键——此前这类条目注册了物品但永不掉落。
                    String key = type != null ? type.name() : entity.toUpperCase(Locale.ROOT);
                    drops.computeIfAbsent(key, k -> new ArrayList<>()).add(new Drop(effId, key, chance));
                }
                ok++;
            } catch (Exception e) {
                WT.log("mob_drops.yml " + id + " 注册失败: " + e);
                skip++;
            }
        }
        WT.plugin.getLogger().info("mob_drops.yml: 注册 " + ok + ", 跳过 " + skip);
    }

    /**
     * 自定义掉落展示配方类型（worldtaste:mob_drop）：图标为僵尸刷怪蛋，命名「生物掉落」。
     * 不用内置 RecipeType.MOB_DROP 的原因见类注释（fork 会解析配方第 5 格显示名 → 崩溃/重复掉落）。
     */
    private static RecipeType mobDropType() {
        if (mobDropType == null) {
            ItemStack icon = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("生物掉落", NamedTextColor.AQUA));
                meta.lore(List.of(Component.text("击杀对应生物有概率获得", NamedTextColor.GRAY)));
                icon.setItemMeta(meta);
            }
            mobDropType = new RecipeType(new NamespacedKey(WT.plugin, "mob_drop"), icon);
        }
        return mobDropType;
    }

    /** 解析实体类型；兼容旧枚举名（MUSHROOM_COW→MOOSHROOM、SNOWMAN→SNOW_GOLEM），失败返回 null。 */
    private static EntityType parseEntityType(String name) {
        if (name == null || name.isEmpty()) return null;
        String u = name.toUpperCase(Locale.ROOT);
        switch (u) {
            case "MUSHROOM_COW": u = "MOOSHROOM"; break;
            case "SNOWMAN": u = "SNOW_GOLEM"; break;
            case "ZOMBIE_PIGMAN": u = "ZOMBIFIED_PIGLIN"; break;
            default: break;
        }
        try {
            return EntityType.valueOf(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 构建掉落来源展示蛋（对齐 RSC MobDropsReader：实体刷怪蛋 + 「击杀 XX 时会有 N% 的概率掉落」
     * lore，生物名用 translatable，由客户端按语言文件渲染中文名）。无对应刷怪蛋材质的实体回退
     * EGG 图标（与 RSC 一致）。构建失败返回 null（该条目退回无配方展示，不影响注册与掉落）。
     * 注意：蛋只带 lore 不带显示名——显示名会被 fork 的 registerMobDrop 解析，须保持无关。
     */
    private static ItemStack dropSourceEgg(EntityType type, int chance) {
        try {
            Material eggMat = Material.matchMaterial(type.name() + "_SPAWN_EGG");
            if (eggMat == null) eggMat = Material.EGG;
            ItemStack egg = new ItemStack(eggMat);
            ItemMeta meta = egg.getItemMeta();
            if (meta == null) return null;
            Component lore = Component.text()
                    .append(Component.text("击杀 ", NamedTextColor.GREEN))
                    .append(Component.translatable(type.translationKey()).color(NamedTextColor.AQUA))
                    .append(Component.text(" 时会有 ", NamedTextColor.GREEN))
                    .append(Component.text(chance + "%", NamedTextColor.AQUA))
                    .append(Component.text(" 的概率掉落", NamedTextColor.GREEN))
                    .build();
            meta.lore(List.of(lore));
            egg.setItemMeta(meta);
            return egg;
        } catch (Exception e) {
            WT.log("mob_drops " + type + ": 掉落来源展示构建失败，退回无配方: " + e);
            return null;
        }
    }

    public static final class Drop {
        public final String itemId;
        public final String entity;
        public final int chance;
        Drop(String itemId, String entity, int chance) {
            this.itemId = itemId;
            this.entity = entity;
            this.chance = chance;
        }
    }
}
