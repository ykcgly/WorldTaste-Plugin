package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 甜度试纸：主手拿果汁/饮品（带糖分 PDC），副手持试纸，右键空气/方块后
 * 副手消耗一张试纸，在聊天栏输出当前手持饮品的糖分值。
 *
 * <p>判定依据为 {@link JuicerRecipe#KEY_ITEM_SUGAR}——榨汁盆瓶装/桶装产物与酒窖
 * 成品均写入该 PDC；无糖分数据的物品不触发检测（不消耗试纸）。右键时取消事件，
 * 避免药水果汁被原版饮用流程吞掉。</p>
 */
public final class SweetnessPaper extends SlimefunItem {

    public static final String ID = "WT_SWEET_PAPER";

    private SweetnessPaper(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                           ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }

    /** 注册内部物品与检测监听（Setup 调用；无配方，配方待补）。 */
    public static void register(ItemGroup group) {
        if (group == null) {
            WT.log("甜度试纸注册失败：物品组缺失");
            return;
        }
        ItemStack template = new ItemStack(Material.PAPER);
        ItemMeta meta = template.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Colors.c("&e&l甜度试纸"));
            meta.setLore(List.of(
                    Colors.c("&7可以用它来检测饮品的甜度"),
                    Colors.c("&8主手持果汁，副手持试纸，右键空气检测")));
            template.setItemMeta(meta);
        }
        SlimefunItemStack stack = new SlimefunItemStack(ID, template);
        new SweetnessPaper(group, stack, RecipeType.NULL, new ItemStack[0]).register(WT.plugin);
        Bukkit.getPluginManager().registerEvents(new DetectListener(), WT.plugin);
    }

    /** 读取饮品的糖分 PDC（未写入糖分数据的物品返回 null = 不触发检测）。 */
    private static Integer readSugar(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER);
    }

    private static final class DetectListener implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (e.getHand() != EquipmentSlot.HAND) return;
            if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            Integer sugar = readSugar(e.getItem());
            if (sugar == null) return;
            Player p = e.getPlayer();
            ItemStack off = p.getInventory().getItemInOffHand();
            if (!(SlimefunItem.getByItem(off) instanceof SweetnessPaper)) return;
            // 取消原版交互（果汁多为药水材质，防止右键时被喝掉）
            e.setCancelled(true);
            if (off.getAmount() > 1) off.setAmount(off.getAmount() - 1);
            else p.getInventory().setItemInOffHand(null);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            if (sugar <= 0) {
                p.sendMessage("§e甜度检测：§7当前饮品几乎不含糖分。");
            } else {
                p.sendMessage("§e甜度检测：§f当前饮品的糖分为 §b" + sugar + "§e 点。");
            }
        }
    }
}
