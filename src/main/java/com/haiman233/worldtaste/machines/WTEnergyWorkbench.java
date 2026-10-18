package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 耗电型工作台（yml {@code energy: true}，默认；如百味万用炉）：在纯工作台
 * {@link WTWorkbench} 之上实现 {@link EnergyNetComponent}——点击合成前校验并扣除电力。
 *
 * <p>注册校验要求电力组件 capacity/energyConsumption 均 ≥1（yml 保证；缺失时 loader 默认
 * 128/8 兜底）。</p>
 */
public class WTEnergyWorkbench extends WTWorkbench implements EnergyNetComponent {

    private final int capacity;
    private final int consumption;

    public WTEnergyWorkbench(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                             int[] input, int[] output, List<WTRecipe> recipes,
                             int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                             int clickSlot) {
        super(group, item, rt, recipe, input, output, recipes,
                capacity, consumption, speed, menu, hideAll, clickSlot, true);
        this.capacity = Math.max(1, capacity);
        this.consumption = Math.max(1, Math.min(consumption, Math.max(1, capacity)));
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    /** 单次合成耗电（业务参数；EnergyNetComponent 接口无此方法，仅本类使用）。 */
    public int getEnergyConsumption() {
        return consumption;
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    /** 点击合成前校验并扣除电力（没电 → 安全失败：不消耗材料不产出）。 */
    @Override
    protected boolean consumeEnergy(BlockMenu menu) {
        org.bukkit.Location loc = menu.getLocation();
        if (getCharge(loc) < consumption) return false;
        removeCharge(loc, consumption);
        return true;
    }
}
