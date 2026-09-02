package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import org.bukkit.block.Block;

/**
 * 酒窖管理器（machines.yml 中 script: jiujiao，材质酿造台）：酒窖多方块核心。
 *
 * <p>储电 500J，耗电 100J/t（仅多方块结构完整且蓄电充足时自 charge 扣除，
 * 电网会自动向其补电）。右键始终打开 {@link CellarMenu} 机器页面（结构不完整时
 * 提示但不拦截，方便玩家查看页面内的多方块结构展示）。
 * 运行中双机（管理器/温控器）任一蓄电不足 → 酿造/陈化直接失败产生废液。</p>
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
                // 断电：酿造/陈化直接失败，液体报废（结构被拆仍为暂停，破坏事件本身会清数据）
                if (getCharge(b.getLocation()) < CONSUMPTION
                        || ctrl.getCharge(partner.getLocation())
                                < TemperatureController.CONSUMPTION) {
                    boolean brew = st.mode() == WineCellarState.Mode.BREW;
                    st.contaminate();
                    st.save(b);
                    b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                    Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
                    if (placer != null) {
                        placer.sendMessage("§c酒窖电力中断，" + (brew ? "酿造" : "陈化")
                                + "失败，液体已全部报废！");
                    }
                    return;
                }
                // 双机电力协调：本轮从管理器与温控器各扣除消耗
                removeCharge(b.getLocation(), CONSUMPTION);
                ctrl.removeCharge(partner.getLocation(), TemperatureController.CONSUMPTION);
                CellarMenu.advance(st, b);
            }
        });

        addItemHandler(new BlockUseHandler() {
            @Override
            public void onRightClick(PlayerRightClickEvent e) {
                e.getClickedBlock().ifPresent(b -> {
                    e.cancel();
                    // 结构不完整也允许打开机器页面：玩家可在页面内通过「多方块结构」按钮查看搭建方式
                    // （计时推进仍要求结构完整 + 双机供电，不完整时只是提示，不拦截查看）
                    if (!CellarStructure.matches(b, false)) {
                        e.getPlayer().sendMessage("§c多方块结构不完整，机器无法运行！");
                    }
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
