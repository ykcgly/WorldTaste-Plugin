package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.guide.DecorativeSubGroup;
import com.haiman233.worldtaste.guide.WTNestedGroup;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SeasonalItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import java.time.Month;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加载 groups.yml：nested / sub / button / seasonal / locked / normal。 */
public final class GroupLoader {

    private GroupLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "groups.yml");
        // 先注册 nested 根组，再注册子组（SubItemGroup 构造需要父 NestedItemGroup）
        // 逐条 try/catch 故障隔离：registerNested 内 Read.item(PlayerHead/Skin) 或组构造/注册
        // 可能对单条坏数据抛异常；若无隔离会中止整个 GroupLoader，其后所有物品因「物品组缺失」被跳过。
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (type.equals("nested") || type.equals("parent")) registerNested(key, s);
            } catch (Exception e) {
                WT.log("groups " + key + " 注册失败，跳过: " + e);
            }
        }
        int ok = WT.groups.size();
        for (String key : y.getKeys(false)) {
            try {
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                String type = s.getString("type", "normal").toLowerCase(Locale.ROOT);
                if (type.equals("nested") || type.equals("parent")) continue;
                registerChild(key, s, type);
            } catch (Exception e) {
                WT.log("groups " + key + " 注册失败，跳过: " + e);
            }
        }
        WT.plugin.getLogger().info("groups.yml: 注册 " + (WT.groups.size() - ok) + " 子组，共 " + WT.groups.size());
    }

    private static void registerNested(String key, ConfigurationSection s) {
        ItemStack display = Read.item(s.getConfigurationSection("item"), false);
        if (display == null) {
            WT.log("groups " + key + ": 无展示物品");
            return;
        }
        int tier = s.getInt("tier", 3);
        NestedItemGroup g = WTNestedGroup.create(nsKey(key), display, tier);
        g.register(WT.plugin);
        WT.groups.put(key.toLowerCase(Locale.ROOT), g);
    }

    private static void registerChild(String key, ConfigurationSection s, String type) {
        ItemStack display = Read.item(s.getConfigurationSection("item"), false);
        if (display == null) {
            WT.log("groups " + key + ": 无展示物品");
            return;
        }
        int tier = s.getInt("tier", 3);
        switch (type) {
            case "seasonal": {
                int month = s.getInt("month", 1);
                SeasonalItemGroup g = new SeasonalItemGroup(nsKey(key), Month.of(Math.max(1, Math.min(12, month))), tier, display);
                g.register(WT.plugin);
                WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                break;
            }
            case "button": {
                // 装饰分隔板/标签（RSC 中 actions: none 的对应物）：注册为 DecorativeSubGroup——
                // 展示为普通原版玻璃板（无名字/lore）；原版指南点击无反应；
                // JEG 指南点击由 JegGuideListener 取消 ItemGroupButtonClickEvent，同样无反应
                String parentId = s.getString("parent");
                ItemGroup parent = parentId == null ? null : WT.groups.get(parentId.toLowerCase(Locale.ROOT));
                if (parent instanceof NestedItemGroup nested) {
                    DecorativeSubGroup g = new DecorativeSubGroup(nsKey(key), nested, display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                }
                // 无嵌套父组的 button：纯装饰无承载，直接忽略（注册为普通组会混入指南主菜单）
                break;
            }
            case "sub":
            default: {
                String parentId = s.getString("parent");
                ItemGroup parent = parentId == null ? null : WT.groups.get(parentId.toLowerCase(Locale.ROOT));
                if (parent instanceof NestedItemGroup) {
                    SubItemGroup g = new SubItemGroup(nsKey(key), (NestedItemGroup) parent, display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                } else {
                    // 无 nested 父组：退化为普通组
                    ItemGroup g = new ItemGroup(nsKey(key), display, tier);
                    g.register(WT.plugin);
                    WT.groups.put(key.toLowerCase(Locale.ROOT), g);
                }
                break;
            }
        }
    }

    private static NamespacedKey nsKey(String key) {
        return new NamespacedKey(WT.plugin, key.toLowerCase(Locale.ROOT));
    }
}