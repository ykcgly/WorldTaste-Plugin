package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.behavior.Behaviors.Potion;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 消耗型食物（右键食用）。覆盖原 WT_eatConsumable 与独立食物脚本(yl/tang/jiu/yan/zhongdu 等)：
 * 主手消耗、副手校验(默认禁粘液物品；offhandTool 指定必备工具如 yan 打火石/xuejia 剪刀)、按 opts 恢复饥饿/饱和/消耗/空气/冻结并施加药水。
 */
public class ConsumableItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {

    private final ConsumableOpts opts;

    public ConsumableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, ConsumableOpts opts) {
        super(group, item, rt, recipe);
        this.opts = opts;
    }

    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            Player p = e.getPlayer();
            // 潜行右键不食用：让出放置行为（食物可放置且挖掘保留粘液数据，见 PlantGuardListener）
            if (p.isSneaking()) return;
            if (opts.requireHungry && p.getFoodLevel() >= 20) return;
            PlayerInventory inv = p.getInventory();
            ItemStack off = inv.getItemInOffHand();

            if (opts.offhandTool != null) {
                if (off == null || off.getType() != opts.offhandTool) {
                    p.sendMessage("您必须使用主手且副手持有 " + opts.offhandTool.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + "！");
                    return;
                }
            } else if (off != null && SlimefunItem.getByItem(off) != null) {
                p.sendMessage("您必须使用主手进食且副手不能持有粘液科技物品！");
                return;
            }

            ItemStack main = inv.getItemInMainHand();
            if (main == null || main.getAmount() <= 0) return;
            // 到 0 必须清空主手槽位，避免 0 数量幽灵物品残留（否则下次右键仍被识别/显示）
            Stacks.consumeOneInMainHand(inv);
            if (opts.offhandTool != null && opts.consumeOffhand) {
                // 副手工具为整件消耗（对齐原 yan.js 打火石 / xuejia.js 剪刀的 setAmount-1）：
                // 到 0 必须清空副手，否则 0 数量工具仍能通过 getType() 校验导致无限使用。
                Stacks.consumeOneInOffHand(inv);
            }

            int food = opts.randomFood != null ? (ThreadLocalRandom.current().nextInt(opts.randomFood) + 1)
                    : (opts.food != null ? opts.food.intValue() : 0);
            if (opts.foodSet != null) p.setFoodLevel(opts.foodSet);
            else if (food > 0) p.setFoodLevel(p.getFoodLevel() + food);
            if (opts.saturationSet != null) p.setSaturation(opts.saturationSet);
            else if (opts.saturation != null) p.setSaturation((float) (p.getSaturation() + opts.saturation));
            if (opts.exhaustion != null) p.setExhaustion((float) (p.getExhaustion() - opts.exhaustion));
            if (opts.exhaustionSet != null) p.setExhaustion(opts.exhaustionSet.floatValue());
            if (opts.absorption != null) p.setAbsorptionAmount(opts.absorption);
            if (opts.remainingAirAdd != null) p.setRemainingAir(p.getRemainingAir() + opts.remainingAirAdd);
            if (opts.gameMode != null) {
                try { p.setGameMode(org.bukkit.GameMode.valueOf(opts.gameMode.toUpperCase(java.util.Locale.ROOT))); }
                catch (IllegalArgumentException ignored) {}
            }
            if (opts.satRegen != null) p.setSaturatedRegenRate(opts.satRegen);
            if (opts.unsatRegen != null) p.setUnsaturatedRegenRate(opts.unsatRegen);
            if (opts.starvation != null) p.setStarvationRate(opts.starvation);
            if (opts.maxAir != null) p.setMaximumAir(opts.maxAir);
            if (opts.remainingAir != null) p.setRemainingAir(opts.remainingAir);
            if (opts.freezeTicks != null) p.setFreezeTicks(opts.freezeTicks);

            for (Potion pt : opts.potions) {
                PotionEffectType type = PotionEffectType.getByName(pt.type);
                if (type != null) p.addPotionEffect(new PotionEffect(type, pt.duration, pt.amplifier, false));
                else WT.log("未知药水类型: " + pt.type);
            }

            // 异域花园联动：酒类饮品（items.yml alcohol 字段）饮用后累加其酒精度
            ExoticGardenHook.onDrink(p, this.getId());

            if (opts.message != null) p.sendMessage(opts.message);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_STRIDER_EAT, 1f, 1f);
        };
    }
}
