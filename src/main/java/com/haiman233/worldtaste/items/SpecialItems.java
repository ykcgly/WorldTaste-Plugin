package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 特殊脚本物品（原独立 .js 脚本），全部为手写 Java 实现。 */
public final class SpecialItems {

    private SpecialItems() {}

    public static SlimefunItem create(String id, ItemGroup group, SlimefunItemStack sfis,
                                      RecipeType rt, ItemStack[] recipe, String script) {
        switch (script) {
            case "yurenjie/buyunping": return new CloudBottleItem(group, sfis, rt, recipe);
            case "jurenwan": return new GiantPillItem(group, sfis, rt, recipe);
            default: return null;
        }
    }

    /** 捕云瓶：仅在云层(Y=192-196)可用；晴天掉落 WT_CLOUD，雨/雷天掉落 WT_THUNDERCLOUD。 */
    private static class CloudBottleItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {
        CloudBottleItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) { super(g, i, rt, r); }

        @Override
        public ItemUseHandler getItemHandler() {
            return e -> {
                Player p = e.getPlayer();
                Location l = p.getLocation();
                if (l.getY() < 192 || l.getY() > 196) {
                    p.sendMessage("§c您必须在云层(Y=192-196)才能使用捕云瓶！");
                    return;
                }
                ItemStack off = p.getInventory().getItemInOffHand();
                if (off != null && SlimefunItem.getByItem(off) != null) {
                    p.sendMessage("您必须使用主手捕云且副手不能持有粘液科技物品！");
                    return;
                }
                ItemStack main = p.getInventory().getItemInMainHand();
                if (main == null || main.getAmount() <= 0) return;

                boolean clear = !p.getWorld().hasStorm() && !p.getWorld().isThundering();
                String dropId = clear ? "WT_CLOUD" : "WT_THUNDERCLOUD";
                SlimefunItem sf = SlimefunItem.getById(dropId);
                if (sf == null) {
                    // 掉落物未注册：不消耗瓶子（避免吞物品），仅记录并提示
                    WT.log("捕云瓶掉落物未注册: " + dropId);
                    return;
                }
                // 到 0 必须清空主手槽位，避免 0 数量幽灵物品残留
                Stacks.consumeOneInMainHand(p.getInventory());
                p.getWorld().dropItemNaturally(l, sf.getItem().clone());
                p.sendMessage("§b成功捕获了" + (clear ? "云朵" : "乌云") + "！");
                p.getWorld().playSound(l, Sound.ENTITY_PLAYER_SPLASH, 1f, 1f);
            };
        }
    }

    /** 巨人丸：右键在瞄准方块上方生成一只巨人（GIANT）。 */
    private static class GiantPillItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {
        GiantPillItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) { super(g, i, rt, r); }

        @Override
        public ItemUseHandler getItemHandler() {
            return e -> {
                Player p = e.getPlayer();
                if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
                    p.sendMessage("请主手持有相应物品");
                    return;
                }
                org.bukkit.inventory.PlayerInventory inv = p.getInventory();
                ItemStack off = inv.getItemInOffHand();
                if (off != null && SlimefunItem.getByItem(off) != null) {
                    p.sendMessage("您必须使用主手且副手不能持有粘液科技物品！");
                    return;
                }
                ItemStack main = inv.getItemInMainHand();
                if (main == null || main.getAmount() <= 0) return;
                // 先校验瞄准方块再消耗：准星无方块（如望向天空）时不消耗，避免吞物品。
                // 不用 getTargetBlock(Set,int)：其永不为 null，无目标时返回视野末端空气方块，
                // 会把巨人凭空生成在半空。
                Block target = p.getTargetBlockExact(5);
                if (target == null || target.getType().isAir()) {
                    p.sendMessage("§c请瞄准 5 格内的方块使用巨人丸！");
                    return;
                }
                // 到 0 必须清空主手槽位，避免 0 数量幽灵物品残留
                Stacks.consumeOneInMainHand(inv);
                Location loc = target.getLocation().add(0, 1, 0);
                loc.getWorld().spawnEntity(loc, EntityType.GIANT);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_STRIDER_EAT, 1f, 1f);
            };
        }
    }
}
