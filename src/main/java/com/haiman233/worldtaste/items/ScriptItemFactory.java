package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.behavior.Behaviors;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.behavior.Behaviors.CropCfg;
import com.haiman233.worldtaste.machines.JuicerBasin;
import com.haiman233.worldtaste.machines.TemperatureController;
import com.haiman233.worldtaste.machines.WineCellarManager;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import org.bukkit.inventory.ItemStack;

/**
 * 按 {@link ItemSpec}（脚本 + 属性）分派物品子类。覆盖 RSC ItemReader 的各分支：
 * 作物 / 消耗品(含放射性) / 能量 / 猪灵 / 灵魂绑定 / 防凋灵 / 放射性方块 / 普通(可否放置)。
 */
public final class ScriptItemFactory {

    private ScriptItemFactory() {}

    public static SlimefunItem create(ItemSpec spec, ItemGroup group, SlimefunItemStack sfis,
                                      RecipeType rt, ItemStack[] recipe) {
        // energy 分支（忽略 script），对齐 RSC
        if (spec.energyCapacity != null) {
            return new AttributeItems.EnergyItem(group, sfis, rt, recipe, spec.energyCapacity);
        }

        Radioactivity rad = AttributeItems.parseRadiation(spec.radiation);

        if (spec.script != null) {
            CropCfg crop = Behaviors.crops.get(spec.script);
            if (crop != null) return new CropBlock(group, sfis, rt, recipe, crop);
            ConsumableOpts opts = Behaviors.consumables.get(spec.script);
            if (opts != null && opts.use) {
                if (rad != null) return new AttributeItems.RadioactiveConsumable(group, sfis, rt, recipe, opts, rad);
                return new ConsumableItem(group, sfis, rt, recipe, opts);
            }
            // 榨汁盆（machines.yml script: zhapen）：交互式方块机器
            if ("zhapen".equals(spec.script)) return new JuicerBasin(group, sfis, rt, recipe);
            // 酒窖多方块：管理器（核心，带校验交互）+ 温度控制器（无 UI 能源方块）
            if ("jiujiao".equals(spec.script)) return new WineCellarManager(group, sfis, rt, recipe);
            if ("wendu".equals(spec.script)) return new TemperatureController(group, sfis, rt, recipe);
            SlimefunItem special = SpecialItems.create(spec.id, group, sfis, rt, recipe, spec.script);
            if (special != null) return special;
        }

        if (rad != null) return new AttributeItems.RadioactiveItem(group, sfis, rt, recipe, rad);
        if (spec.soulbound) return new AttributeItems.SoulboundItem(group, sfis, rt, recipe);
        if (spec.antiWither) return new AttributeItems.WitherProofItem(group, sfis, rt, recipe);
        if (spec.piglinChance != null) return new AttributeItems.PiglinBarterItem(group, sfis, rt, recipe, spec.piglinChance);
        if (!spec.placeable) return new WTUnplaceableItem(group, sfis, rt, recipe);
        return new WTItem(group, sfis, rt, recipe);
    }
}
