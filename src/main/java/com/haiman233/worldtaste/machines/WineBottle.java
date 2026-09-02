package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 陈酿果酒（WT_WINE，内部物品，酒窖出酒产出，不在指南提供配方）。
 *
 * <p>每瓶携带 PDC：juice_wine 标记（禁止再次投入酒窖）、juice_alcohol（酒精度，每瓶一致）、
 * juice_contents（组成果汁）、juice_sugar、juice_players（榨汁师）。饮用（原版喝完药水的
 * {@link PlayerItemConsumeEvent}）后按酒精度增加玩家酒精度（联动异域花园醉酒效果）；
 * 原版饮用会自动返还玻璃瓶。</p>
 *
 * <p>注意：不能用右键 ItemUseHandler 实现饮用——POTION 材质右键会被原版喝药流程接管，
 * 处理器不可靠；统一走 PlayerItemConsumeEvent。</p>
 */
public final class WineBottle extends SlimefunItem {

    private WineBottle(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                       org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }

    /** 注册内部物品与饮用监听（Setup 调用；无配方，仅酒窖产出）。 */
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
        new WineBottle(group, stack, RecipeType.NULL, new ItemStack[0]).register(WT.plugin);
        Bukkit.getPluginManager().registerEvents(new DrinkListener(), WT.plugin);
    }

    /** 读取果酒酒精度（非果酒返回 0）。 */
    public static int readAlcohol(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        Double v = meta.getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE);
        return v == null ? 0 : (int) Math.round(v);
    }

    /**
     * 构建一瓶果酒：基础模板 + 酒窖信息 lore（wine-lore-format 渲染）+ PDC 数据
     * （wine 标记 / 酒精度 / 组成 / 糖分 / 榨汁师）。
     */
    public static ItemStack create(double alcohol, int sugar, Map<String, Double> contents,
                                   Set<String> players) {
        ItemStack out = preview(alcohol, sugar, contents, players);
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE, alcohol);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    JuicerRecipe.joinContentsFractional(contents));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 果酒预览（不含 PDC 数据，酒窖出酒口展示用）。 */
    public static ItemStack preview(double alcohol, int sugar, Map<String, Double> contents,
                                    Set<String> players) {
        ItemStack out = new ItemStack(Material.POTION);
        ItemMeta meta = out.getItemMeta();
        if (meta instanceof PotionMeta pm) {
            pm.setColor(Color.fromRGB(110, 20, 40));
            pm.setDisplayName(Colors.c("&5&l陈酿果酒"));
            pm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            // 与榨汁盆产物相同：整行 & 颜色码经 Colors.c 后 setLore。
            // PotionMeta.lore(Component) 不会翻译 juicer.yml 里的 & 码，会原样显示。
            List<String> lore = new ArrayList<>();
            lore.add(Colors.c("&7酒窖陈酿的果酒，右键饮用"));
            lore.add(renderInfo(JuicerRecipe.wineLoreFormat, players, contents, alcohol));
            pm.setLore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 按 wine-lore-format 渲染果酒信息行（占位符：%players%/%contents%/%alcohol%）。 */
    private static String renderInfo(String format, Set<String> players,
                                     Map<String, Double> contents, double alcohol) {
        String playersText = players == null || players.isEmpty() ? "无" : String.join("、", players);
        String line = format
                .replace("%players%", playersText)
                .replace("%contents%", contentsLegacy(contents))
                .replace("%alcohol%", String.format("%.1f", alcohol));
        return Colors.c(line);
    }

    /** 组成果汁名（粘液显示名保留自身颜色码；原版用翻译组件再转旧式文本；分数显示百分比）。 */
    private static String contentsLegacy(Map<String, Double> contents) {
        if (contents == null || contents.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> en : contents.entrySet()) {
            if (sb.length() > 0) sb.append("、");
            ItemStack it = JuicerRecipe.refToItem(en.getKey());
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                sb.append(it.getItemMeta().getDisplayName());
            } else {
                sb.append(LegacyComponentSerializer.legacySection()
                        .serialize(JuicerRecipe.nameComponent(en.getKey())));
            }
            sb.append(JuicerRecipe.quantityText(en.getValue()));
        }
        return sb.toString();
    }

    /** 饮用监听：喝下果酒时按酒精度累加玩家酒精度（联动异域花园）。 */
    private static final class DrinkListener implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onConsume(PlayerItemConsumeEvent e) {
            // 识别走自建 PDC 标记：酒瓶由普通 ItemStack 构建，不带 Slimefun id，
            // getByItem 永远返回 null，不能用 instanceof 判定
            ItemStack consumed = e.getItem();
            if (consumed == null || !consumed.hasItemMeta()) return;
            if (!consumed.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) return;
            Player p = e.getPlayer();
            int alcohol = readAlcohol(consumed);
            if (alcohol > 0) ExoticGardenHook.addAlcoholDirect(p, alcohol);
        }
    }
}
