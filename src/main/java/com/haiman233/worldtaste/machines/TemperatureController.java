package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;

/**
 * 温度控制器（machines.yml 中 script: wendu，材质涂蜡铜块）：酒窖多方块的配套能源方块，
 * 无 UI。储电 500J，耗电 50J/t。
 *
 * <p>自身无独立 ticker：酒窖运行（酿造/陈化计时推进）时由管理器 ticker 协调双机耗电——
 * 管理器扣 100J/t、本方块扣 50J/t，任一蓄电不足则当轮暂停。电网会自动向其补电。</p>
 *
 * <p>耗电量经 {@link PowerConsumer} 暴露，注册时自动写入物品 lore。</p>
 */
public class TemperatureController extends SlimefunItem implements EnergyNetComponent, PowerConsumer {

    public static final int CAPACITY = 500;
    public static final int CONSUMPTION = 50;

    public TemperatureController(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                                 org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    @Override
    public int getConsumption() {
        return CONSUMPTION;
    }
}
