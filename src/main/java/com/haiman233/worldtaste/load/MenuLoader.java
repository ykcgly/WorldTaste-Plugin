package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.MenuDef;
import com.haiman233.worldtaste.util.Colors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** 加载 menus.yml → {@link WT#menus}（供机器取 GUI 布局）。支持 slots 单槽与 a-b 区间写法。 */
public final class MenuLoader {

    private MenuLoader() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "menus.yml");
        int ok = 0;
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            try {
                MenuDef menu = new MenuDef(id, Colors.c(s.getString("title", id)));
                int size = s.getInt("size", -1);
                if (size > 0) menu.size = size;
                ConfigurationSection slots = s.getConfigurationSection("slots");
                if (slots != null) {
                    for (String key : slots.getKeys(false)) {
                        ConfigurationSection slotSec = slots.getConfigurationSection(key);
                        if (slotSec == null) continue;
                        ItemStack item = Read.item(slotSec, false);
                        boolean isProgress = slotSec.getBoolean("progressbar", false)
                                || slotSec.isConfigurationSection("progressbar");
                        for (int slot : parseSlots(key)) {
                            if (item != null) menu.items.put(slot, item);
                            if (isProgress) {
                                menu.progressSlot = slot;
                                menu.progressItem = item;
                            }
                        }
                    }
                }
                WT.menus.put(id, menu);
                ok++;
            } catch (Exception e) {
                WT.log("menus.yml " + id + " 失败: " + e);
            }
        }
        WT.plugin.getLogger().info("menus.yml: 注册 " + ok);
    }

    /** 解析槽位键：单值 "6" 或区间 "10-17"。非法/反转/越界（>53）返回空数组（仅跳过该槽，不连累整个菜单）。 */
    private static int[] parseSlots(String key) {
        key = key.trim();
        int dash = key.indexOf('-');
        if (dash > 0) {
            try {
                int lo = Integer.parseInt(key.substring(0, dash));
                int hi = Integer.parseInt(key.substring(dash + 1));
                // 反转区间(如 17-10)曾导致 NegativeArraySizeException 逃出 NumberFormatException
                // 捕获、连累整个菜单注册失败；越界槽位(>53)则会在 constructMenu 越界炸掉机器注册，
                // 此处一并校验后返回空数组，实现故障隔离。
                if (lo >= 0 && hi < 54 && hi >= lo) {
                    int[] out = new int[hi - lo + 1];
                    for (int i = 0; i < out.length; i++) out[i] = lo + i;
                    return out;
                }
            } catch (NumberFormatException ignored) { }
        }
        try {
            int v = Integer.parseInt(key);
            return (v >= 0 && v < 54) ? new int[] { v } : new int[0];
        } catch (NumberFormatException e) {
            return new int[0];
        }
    }
}
