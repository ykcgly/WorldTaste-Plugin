package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerHead;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerSkin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * 共享读取器：把一个物品段（{@code material_type/material/name/lore/glow/amount}）解析为 {@link ItemStack}，
 * 以及把配方段（槽位 1..N）解析为 {@code ItemStack[]}。对应 RSC 的 {@code CommonUtils.readItem/readRecipe}。
 */
public final class Read {

    private Read() {}

    private static final Pattern HEX64 = Pattern.compile("^[0-9A-Fa-f]{64}$");

    /**
     * R7：头颅贴图（{@link PlayerSkin}）去重缓存，按类型分表（避免键拼接分配，且杜绝跨类型键碰撞）。
     * PlayerSkin 为不可变值，跨调用方共享安全；{@link PlayerHead#getItemStack} 每次仍新建独立 ItemStack。
     * 实测全部内容文件 skull 解码共 3203 次、其中仅 182 次（5.7%）为重复（如某些通用装饰头/配方槽复用），
     * 而 dough {@code PlayerSkin.fromHashCode/fromBase64/fromURL} 无内部缓存——每次含 MD5
     * （{@code UUID.nameUUIDFromBytes}）、JSON 拼接、Base64 编码、URL 解析。去重省去这 182 次重复解码。
     * 加载后由 {@link Setup#loadAll()} 调用 {@link #clearSkinCache()} 释放（Read 仅加载期使用）。
     */
    private static final Map<String, PlayerSkin> HASH_SKINS = new HashMap<>();
    private static final Map<String, PlayerSkin> BASE64_SKINS = new HashMap<>();
    private static final Map<String, PlayerSkin> URL_SKINS = new HashMap<>();

    /** 读取物品段。{@code countable=true} 时应用 amount。 */
    public static ItemStack item(ConfigurationSection s, boolean countable) {
        if (s == null) return null;
        String material = s.getString("material", "");
        if (material.isEmpty()) return null;

        String type = s.getString("material_type", "mc");
        String lower = material.toLowerCase(java.util.Locale.ROOT);
        // 自动识别覆盖（与 RSC CommonUtils.readItem 一致，前缀判断区分大小写：base64 贴图以小写 ey 开头）
        if (material.startsWith("ey") || material.startsWith("ew")) type = "skull";
        else if (lower.startsWith("http")) type = "skull_url";
        else if (HEX64.matcher(material).matches()) type = "skull_hash";

        ItemStack stack = resolve(type, material);
        if (stack == null) return null;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = s.getString("name");
            if (name != null && !name.isEmpty()) meta.setDisplayName(Colors.c(name));
            List<String> lore = s.getStringList("lore");
            if (lore != null && !lore.isEmpty()) meta.setLore(Colors.c(lore));
            String color = s.getString("color");
            if (color != null && !color.isEmpty()) applyColor(meta, color);
            stack.setItemMeta(meta);
        }

        if (s.getBoolean("glow", false)) Stacks.glow(stack);
        if (countable) {
            int amt = s.getInt("amount", 1);
            if (amt > 0) stack.setAmount(Math.min(amt, stack.getMaxStackSize()));
        }
        return stack;
    }

    private static ItemStack resolve(String type, String material) {
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "none":
                return new ItemStack(Material.AIR);
            case "skull_hash": {
                PlayerSkin skin = HASH_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromHashCode(material); HASH_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "skull":
            case "skull_base64": {
                PlayerSkin skin = BASE64_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromBase64(material); BASE64_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "skull_url": {
                PlayerSkin skin = URL_SKINS.get(material);
                if (skin == null) { skin = PlayerSkin.fromURL(material); URL_SKINS.put(material, skin); }
                return PlayerHead.getItemStack(skin);
            }
            case "slimefun": {
                String id = material.toUpperCase(java.util.Locale.ROOT);
                // 优先使用已注册物品（带 slimefun id PDC）。
                SlimefunItem sf = SlimefunItem.getById(id);
                if (sf != null) return sf.getItem().clone();
                ItemStack pre = WT.preload.get(id);
                if (pre != null) {
                    // 前向引用（目标物品此刻尚未注册，如 items.yml/foods.yml 引用 mob_drops.yml
                    // 的掉落物）：用 SlimefunItemStack 包一层补上 id PDC。裸展示堆缺 id 会导致：
                    // ① 指南配方页点击该材料 SlimefunItem.getByItem 查不到 → 不能跳转到对应
                    // 材料页；② 机器匹配 isItemSimilar 的两端 id 比较失败；③ 作为配方产物发放
                    // 是"有样子无功能"的非粘液物品（多方块机器输出 bug）。补 id 后与注册后的堆
                    // 按 id 等价（meta 细节差异不影响 id 比较与指南导航）。
                    return new SlimefunItemStack(id, pre);
                }
                WT.log("未找到粘液物品: " + id + "，回退为 STONE");
                return new ItemStack(Material.STONE);
            }
            default: {
                Material m = matchMaterial(material);
                if (m == null) {
                    WT.log("未知材质: " + material + "，回退为 STONE");
                    return new ItemStack(Material.STONE);
                }
                return new ItemStack(m);
            }
        }
    }

    private static Material matchMaterial(String name) {
        if (name == null) return null;
        Material m = Material.matchMaterial(name);
        if (m != null) return m;
        // 别名（1.21 改名）
        if (name.equalsIgnoreCase("GRASS")) return Material.matchMaterial("SHORT_GRASS");
        if (name.equalsIgnoreCase("SCUTE")) return Material.matchMaterial("TURTLE_SCUTE");
        return Material.matchMaterial(name.replace('-', '_'));
    }

    /** 加载完成后释放头颅贴图缓存（{@link Setup#loadAll()} 末尾调用；Read 仅加载期使用）。 */
    public static void clearSkinCache() {
        HASH_SKINS.clear();
        BASE64_SKINS.clear();
        URL_SKINS.clear();
    }

    /** 读取配方段，槽位键 "1".."size"，产出长度为 size 的数组（空槽为 null）。 */
    public static ItemStack[] recipe(ConfigurationSection recipeSec, int size) {
        ItemStack[] out = new ItemStack[size];
        if (recipeSec == null) return out;
        for (int i = 0; i < size; i++) {
            ConfigurationSection slot = recipeSec.getConfigurationSection(String.valueOf(i + 1));
            if (slot != null) out[i] = item(slot, true);
        }
        return out;
    }

    /** 应用 "R,G,B" 颜色到皮革护甲/药水等可染色 meta。 */
    private static void applyColor(ItemMeta meta, String color) {
        String[] rgb = color.split(",");
        if (rgb.length != 3) return;
        try {
            int r = Integer.parseInt(rgb[0].trim());
            int g = Integer.parseInt(rgb[1].trim());
            int b = Integer.parseInt(rgb[2].trim());
            Color c = Color.fromRGB(r, g, b);
            if (meta instanceof LeatherArmorMeta) ((LeatherArmorMeta) meta).setColor(c);
            else if (meta instanceof PotionMeta) ((PotionMeta) meta).setColor(c);
        } catch (RuntimeException ignored) {
            WT.log("颜色格式错误: " + color);
        }
    }
}
