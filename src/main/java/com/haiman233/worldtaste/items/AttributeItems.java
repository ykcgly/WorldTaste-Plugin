package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.attributes.PiglinBarterDrop;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactive;
import io.github.thebusybiscuit.slimefun4.core.attributes.Rechargeable;
import io.github.thebusybiscuit.slimefun4.core.attributes.Soulbound;
import io.github.thebusybiscuit.slimefun4.core.attributes.WitherProof;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.inventory.ItemStack;

/**
 * 原 RSC 通过 ByteBuddy 动态叠加属性接口；此处针对 WorldTaste 实际用到的单属性组合预定义类。
 * 覆盖：radioactive(可放置/消耗)、soulbound、wither-proof、piglin-barter、energy。
 */
public final class AttributeItems {

    private AttributeItems() {}

    /** 放射性消耗品（script + radiation，如辐射鱼肉）。 */
    public static class RadioactiveConsumable extends ConsumableItem implements Radioactive {
        private final Radioactivity level;
        public RadioactiveConsumable(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, ConsumableOpts opts, Radioactivity level) {
            super(g, i, rt, r, opts);
            this.level = level;
        }
        @Override
        public Radioactivity getRadioactivity() { return level; }
    }

    /** 放射性方块（placeable + radiation）。 */
    public static class RadioactiveItem extends WTItem implements Radioactive {
        private final Radioactivity level;
        public RadioactiveItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, Radioactivity level) {
            super(g, i, rt, r);
            this.level = level;
        }
        @Override
        public Radioactivity getRadioactivity() { return level; }
    }

    /** 灵魂绑定方块（placeable + soulbound）。 */
    public static class SoulboundItem extends WTItem implements Soulbound {
        public SoulboundItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) {
            super(g, i, rt, r);
        }
    }

    /** 防凋灵方块（placeable + anti_wither）。 */
    public static class WitherProofItem extends WTItem implements WitherProof {
        public WitherProofItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r) {
            super(g, i, rt, r);
        }
        @Override
        public void onAttack(Block block, Wither wither) {
            // 标记接口由 Slimefun 拦截凋灵破坏；此处空实现
        }
    }

    /** 猪灵以物易物掉落（不可放置 + piglin_trade_chance）。 */
    public static class PiglinBarterItem extends WTUnplaceableItem implements PiglinBarterDrop {
        private final int chance;
        public PiglinBarterItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, int chance) {
            super(g, i, rt, r);
            this.chance = chance;
        }
        @Override
        public int getBarteringLootChance() { return chance; }
    }

    /**
     * 可充能手持电池（energy_capacity，对齐 RSC CustomEnergyItem = Rechargeable + NotPlaceable）：
     * 电荷存于物品 meta（充电站可充电），不可放置；无脚本时无使用行为。
     */
    public static class EnergyItem extends WTUnplaceableItem implements Rechargeable {
        private final int capacity;
        public EnergyItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, int capacity) {
            super(g, i, rt, r);
            this.capacity = capacity;
        }
        @Override
        public float getMaxItemCharge(ItemStack item) { return capacity; }
    }

    /**
     * 可充电的食用电池（唯一实例：生日蛋糕电池 WT_DIANCHISRDG，script:12 + energy_capacity:10000）。
     * 右键消耗 {@link #CHARGE_PER_USE} 电量换取饥饿/饱和（用户定版数值），不消耗物品本身——
     * 充满一次（充电站）可食用 capacity/100 次。播放原版食用音效。
     */
    public static class EnergyConsumableItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable, Rechargeable {
        /** 每次食用消耗的电量。 */
        private static final int CHARGE_PER_USE = 100;
        /** 每次食用恢复的饥饿值（2 点 = 1 只鸡腿）。 */
        private static final int FOOD = 2;
        /** 每次食用恢复的饱和度。 */
        private static final float SATURATION = 0.4f;

        private final int capacity;
        public EnergyConsumableItem(ItemGroup g, SlimefunItemStack i, RecipeType rt, ItemStack[] r, int capacity) {
            super(g, i, rt, r);
            this.capacity = capacity;
        }
        @Override
        public float getMaxItemCharge(ItemStack item) { return capacity; }

        @Override
        public ItemUseHandler getItemHandler() {
            return e -> {
                // 一律取消底层交互/放置事件：蛋糕是可放置方块材质，仅靠 NotPlaceable 不足以
                // 拦截全部放置路径（非潜行右键方块面时原版会尝试放置），必须显式 cancel；
                // 食用逻辑由本处理器自行实现，不受底层事件取消影响。
                e.cancel();
                Player p = e.getPlayer();
                // 潜行右键不食用
                if (p.isSneaking()) return;
                if (p.getFoodLevel() >= 20) return; // 饱食时无意义，不耗电
                ItemStack main = p.getInventory().getItemInMainHand();
                if (main == null || main.getAmount() <= 0) return;
                // removeItemCharge 内部含电量校验：不足时不扣除也不生效
                if (!removeItemCharge(main, CHARGE_PER_USE)) {
                    p.sendMessage("电量不足");
                    return;
                }
                p.setFoodLevel(p.getFoodLevel() + FOOD);
                p.setSaturation(p.getSaturation() + SATURATION);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
            };
        }
    }

    public static Radioactivity parseRadiation(String name) {
        if (name == null) return null;
        try {
            return Radioactivity.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
