package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 模板机器（template_machines.yml）：在 templateSlot 放入对应模板物品后，仅匹配该模板下的配方。
 * 不放模板则不合成；模板物品不被消耗。
 */
public class WTTemplateMachine extends WTRecipeMachine {

    private final int templateSlot;
    private final Map<String, List<WTRecipe>> byTemplate;
    /** 模板堆叠数放大产出（对齐 RSC moreOutputIfMoreTemplates：产出 amount *= 模板数量）。 */
    private final boolean moreOutputIfMoreTemplates;
    /** 模板堆叠数加速（fasterIfMoreTemplates：进度推进 *= 模板数量，饲料/电力每秒消耗同比上升）。 */
    private final boolean fasterIfMoreTemplates;

    public WTTemplateMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             int[] input, int[] output, List<WTRecipe> allRecipes,
                             Map<String, List<WTRecipe>> byTemplate,
                             int capacity, int consumption, int speed, MenuDef menu, boolean hideAll, int templateSlot,
                             boolean moreOutputIfMoreTemplates, boolean fasterIfMoreTemplates) {
        super(group, item, rt, recipe, input, output, allRecipes, capacity, consumption, speed, menu, hideAll);
        this.templateSlot = templateSlot;
        this.byTemplate = byTemplate;
        this.moreOutputIfMoreTemplates = moreOutputIfMoreTemplates;
        this.fasterIfMoreTemplates = fasterIfMoreTemplates;
        // super() 末尾重建 preset 时本类字段 templateSlot 尚未赋值（读到 0），
        // 导致真正的模板槽被背景封死。字段就绪后再重建一次 preset。
        createPreset(this, getInventoryTitle(), this::constructMenu);
    }

    @Override
    protected Set<Integer> extraFunctionalSlots() {
        return Collections.singleton(templateSlot);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) {
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        SlimefunItem sf = SlimefunItem.getByItem(tpl);
        if (sf == null) return null;
        List<WTRecipe> list = byTemplate.get(sf.getId().toUpperCase(java.util.Locale.ROOT));
        if (list == null || list.isEmpty()) return null;
        return matchRecipes(inv, list);
    }

    @Override
    protected void pushRecipeOutputs(org.bukkit.block.Block b, BlockMenu inv, WTRecipe r) {
        if (!moreOutputIfMoreTemplates) {
            super.pushRecipeOutputs(b, inv, r);
            return;
        }
        // 对齐 RSC CustomTemplateMachine:274-275：产出数量乘以当前模板堆叠数（模板不被消耗，堆叠持续放大）。
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        int mult = (tpl != null && tpl.getType() != org.bukkit.Material.AIR) ? Math.max(1, tpl.getAmount()) : 1;
        r.pushOutputs(inv, getOutputSlots(), mult);
    }

    /**
     * {@code fasterIfMoreTemplates}：进度推进乘以模板槽内生物堆叠数（N 只 → 速度 ×N）。
     * 每周期饲料消耗量与产出不变、电力仍为 consumption J/s 恒定——由于周期缩短，
     * 饲料与产出的每秒速率都同比 ×N（与产卵室「动物越多速度越快」的语义一致）。
     */
    @Override
    protected int progressPerTick(BlockMenu inv) {
        if (!fasterIfMoreTemplates) return super.progressPerTick(inv);
        ItemStack tpl = inv.getItemInSlot(templateSlot);
        int mult = (tpl != null && tpl.getType() != org.bukkit.Material.AIR) ? Math.max(1, tpl.getAmount()) : 1;
        return getSpeed() * mult;
    }
}
