package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import org.bukkit.inventory.ItemStack;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import org.bukkit.block.Block;

/**
 * 酒窖管理器（machines.yml 中 script: jiujiao，材质酿造台）：酒窖多方块核心。
 *
 * <p>储电 500J，耗电 100J/t（仅多方块结构完整且蓄电充足时自 charge 扣除，
 * 电网会自动向其补电）。右键校验多方块结构：不完整提示「多方块结构不完整！」；
 * 完整时打开 {@link CellarMenu} 机器页面。</p>
 *
 * <p>耗电量经 {@link PowerConsumer} 暴露，注册时自动写入物品 lore。</p>
 */
public class WineCellarManager extends SlimefunItem implements EnergyNetComponent, PowerConsumer {

    public static final int CAPACITY = 500;
    public static final int CONSUMPTION = 100;

    public WineCellarManager(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                             org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);

        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }

            @Override
            public void tick(Block b, SlimefunItem sf, Config data) {
                WineCellarState st = WineCellarState.get(b);
                if (st.phase() != WineCellarState.Phase.RUNNING) return;
                if (!CellarStructure.matches(b, false)) return;
                Block partner = CellarStructure.partner(b, false);
                if (partner == null) return;
                // 伙伴方块必须是已注册的温度控制器
                if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(partner)
                        instanceof TemperatureController ctrl)) return;
                // 双机电力协调：任一蓄电不足则本轮暂停（计时停摆）
                if (getCharge(b.getLocation()) < CONSUMPTION) return;
                if (ctrl.getCharge(partner.getLocation()) < TemperatureController.CONSUMPTION) return;
                removeCharge(b.getLocation(), CONSUMPTION);
                ctrl.removeCharge(partner.getLocation(), TemperatureController.CONSUMPTION);
                CellarMenu.advance(st, b);
            }
        });

        addItemHandler(new BlockUseHandler() {
            @Override
            public void onRightClick(PlayerRightClickEvent e) {
                e.getClickedBlock().ifPresent(b -> {
                    boolean ok = CellarStructure.matches(b, false);
                    e.cancel();
                    if (!ok) {
                        e.getPlayer().sendMessage("§c多方块结构不完整！");
                        return;
                    }
                    // 多方块结构完整：打开机器页面
                    CellarMenu.open(e.getPlayer(), b);
                });
            }
        });
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
