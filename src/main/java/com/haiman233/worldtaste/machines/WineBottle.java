package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Color;

/**
 * 陈酿果酒（WT_WINE，内部物品，酒窖出酒产出，不在指南提供配方）。
 *
 * <p>每瓶携带 PDC：juice_wine 标记（禁止再次投入酒窖）、juice_alcohol（酒精度，每瓶一致）、
 * juice_contents（组成果汁）、juice_sugar、juice_players（榨汁师）。右键饮用后按酒精度
 * 增加玩家酒精度（联动异域花园醉酒效果）。</p>
 */
public final class WineBottle extends SimpleSlimefunItem<ItemUseHandler> {

    private WineBottle(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                       org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);
        addItemHandler(getItemHandler());
    }

    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            Player p = e.getPlayer();
            ItemStack held = p.getInventory().getItemInMainHand();
            int alcohol = readAlcohol(held);
            ExoticGardenHook.addAlcoholDirect(p, alcohol);
            if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else held.setAmount(held.getAmount() - 1);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
        };
    }

    /** 读取果酒酒精度（非果酒返回 0）。 */
    public static int readAlcohol(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Double v = meta.getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE);
        return v == null ? 0 : (int) Math.round(v);
    }

    /** 注册内部物品（Setup 调用；无配方，仅酒窖产出）。 */
    public static void register(ItemGroup group) {
        if (group == null) {
            WT.log("酒窖果酒注册失败：物品组缺失");
            return;
        }
        ItemStack template = new ItemStack(Material.POTION);
        if (template.getItemMeta() instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(110, 20, 40));
            pm.setDisplayName(Colors.c("&5&l陈酿果酒"));
            pm.setLore(List.of(Colors.c("&7酒窖陈酿的果酒，右键饮用")));
            template.setItemMeta(pm);
        }
        SlimefunItemStack stack = new SlimefunItemStack("WT_WINE", template);
        WineBottle wine = new WineBottle(group, stack, RecipeType.NULL, new ItemStack[0]);
        wine.register(WT.plugin);
    }

    /**
     * 构建一瓶果酒：基础模板 + 酒窖信息 lore（wine-lore-format 渲染）+ PDC 数据
     * （wine 标记 / 酒精度 / 组成 / 糖分 / 榨汁师）。
     */
    public static ItemStack create(double alcohol, int sugar, Map<String, Integer> contents,
                                   Set<String> players) {
        ItemStack out = preview(alcohol, sugar, contents, players);
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE, alcohol);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    JuicerRecipe.joinContents(contents));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 果酒预览（不含 PDC 数据，酒窖出酒口展示用）。 */
    public static ItemStack preview(double alcohol, int sugar, Map<String, Integer> contents,
                                    Set<String> players) {
        ItemStack out = new ItemStack(Material.POTION);
        ItemMeta meta = out.getItemMeta();
        if (meta instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(110, 20, 40));
            pm.setDisplayName(Colors.c("&5&l陈酿果酒"));
            List<Component> lore = new ArrayList<>();
            lore.add(leg("&7酒窖陈酿的果酒，右键饮用"));
            lore.add(renderInfo(JuicerRecipe.wineLoreFormat, players, contents, alcohol));
            lore.add(leg("&7糖分: &e" + sugar));
            pm.lore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 按 wine-lore-format 渲染果酒信息行（占位符：%players%/%contents%/%alcohol%）。 */
    private static Component renderInfo(String format, Set<String> players,
                                        Map<String, Integer> contents, double alcohol) {
        String playersText = players == null || players.isEmpty() ? "无" : String.join("、", players);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("%(players|contents|alcohol)%").matcher(format);
        TextComponent.Builder b = Component.text();
        int last = 0;
        // 占位符前的颜色码属于前一个文本片段，需把末尾仍生效的颜色「带」给占位符与尾部文本，
        // 否则 &f%players% / &e%alcohol%° 这类写法的占位符会掉回默认白色。
        String carry = "";
        while (m.find()) {
            String seg = format.substring(last, m.start());
            b.append(leg(seg));
            carry = trailingCodes(Colors.c(seg));
            switch (m.group(1)) {
                case "players" -> b.append(leg(carry + playersText));
                case "contents" -> {
                    boolean first = true;
                    for (Map.Entry<String, Integer> en : contents.entrySet()) {
                        if (!first) b.append(Component.text("、", NamedTextColor.GRAY));
                        first = false;
                        // 果汁名保留其自身颜色（粘液物品显示名可能自带配色）
                        b.append(JuicerRecipe.nameComponent(en.getKey()));
                        if (en.getValue() > 1) {
                            b.append(leg(carry + "×" + en.getValue()));
                        }
                    }
                }
                case "alcohol" -> b.append(leg(carry + String.format("%.1f", alcohol)));
            }
            last = m.end();
        }
        b.append(leg(carry + format.substring(last)));
        return b.build();
    }

    /**
     * 取一段旧式文本末尾<b>仍然生效</b>的颜色/格式码（如 {@code §f}、{@code §x§f§f§0§0§0§0}）。
     * 用于让占位符替换后的文本继承前一个片段的颜色，避免颜色中断。
     */
    private static String trailingCodes(String translated) {
        String color = "";
        String formats = "";
        for (int i = 0; i + 1 < translated.length(); i++) {
            if (translated.charAt(i) != '§') continue;
            char c = Character.toLowerCase(translated.charAt(i + 1));
            if (c == 'x' && i + 14 <= translated.length()) {
                color = translated.substring(i, i + 14);
                formats = "";
                i += 13;
            } else if ("0123456789abcdef".indexOf(c) >= 0) {
                color = "§" + c;
                formats = "";
            } else if ("klmno".indexOf(c) >= 0) {
                if (formats.indexOf(c) < 0) formats += "§" + c;
            } else if (c == 'r') {
                color = "";
                formats = "";
            }
            i++;
        }
        return color + formats;
    }

    /**
     * 旧式 {@code &} 颜色码 → 组件。
     *
     * <p>必须先经过 {@link Colors#c} 翻译：{@link LegacyComponentSerializer} 的
     * legacySection 只认 {@code §} 不认 {@code &}，直接反序列化会让 {@code &8} 这类
     * 配置写法原样显示成文字（本项目 juicer.yml 的 lore 格式统一使用 {@code &}）。</p>
     */
    private static Component leg(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(Colors.c(legacy));
    }
}
