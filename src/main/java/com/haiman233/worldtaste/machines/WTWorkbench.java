package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 工作台（workbenches.yml，如百味万用炉/河豚工作台）：复用 {@link WTRecipeMachine} 的配方/GUI，
 * 但不自动 tick 合成；改为玩家点击 click 槽位时即时合成（匹配并消耗输入、产出）。
 *
 * <p>电力可选（yml {@code energy}，默认 true）：{@code energy: false} 的纯工作台
 * （如河豚工作台）不参与电网——合成不扣电、无蓄电容量，且通过 {@code disableTicker}
 * 退出粘液 tick 周期（tick 本就为空，注册只会白白占用调度）。</p>
 */
public class WTWorkbench extends WTRecipeMachine {

    private final int clickSlot;
    /** 是否耗电合成（yml energy，默认 true；false = 纯工作台，免电免 tick）。 */
    private final boolean usesEnergy;

    public WTWorkbench(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                       int[] input, int[] output, List<WTRecipe> recipes,
                       int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                       int clickSlot, boolean usesEnergy) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        this.clickSlot = clickSlot;
        this.usesEnergy = usesEnergy;
        if (!usesEnergy) {
            // 纯工作台：清空电力参数——容量/单次耗电归 0，不再作为电网组件参与蓄电与请求
            setCapacity(0);
            setEnergyConsumption(0);
        }
        // 用自定义 preset 覆盖 AContainer 默认 preset，在 newInstance 挂接点击合成
        new BlockMenuPreset(getId(), getItemName()) {
            @Override
            public void init() {
                WTWorkbench.this.constructMenu(this);
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                menu.addMenuClickHandler(clickSlot, (player, slot, clicked, action) -> {
                    WTWorkbench.this.craft(menu);
                    return false;
                });
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return flow == ItemTransportFlow.INSERT ? getInputSlots() : getOutputSlots();
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                if (p.hasPermission("slimefun.inventory.bypass")) return true;
                return WTWorkbench.this.canUse(p, false)
                        && (io.github.thebusybiscuit.slimefun4.implementation.Slimefun.instance().isUnitTest()
                        || io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getProtectionManager()
                                .hasPermission(p, b.getLocation(),
                                        io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction.INTERACT_BLOCK));
            }
        };
    }

    /**
     * 点击即时合成的工作台没有合成过程，退出粘液 tick 周期。
     * 实现：AContainer.preRegister() 的唯一职责就是注册逐 tick 的 BlockTicker（字节码实证，
     * 方法体仅 addItemHandler 一个匿名 ticker）——此处整体覆盖且不调 super，ticker 从根源
     * 不注册，不再占用每 tick 调度（tick 空实现仅保留占位）。
     */
    @Override
    public void preRegister() {
        // 不调用 super.preRegister()：跳过 AContainer 的 BlockTicker 注册
    }

    /**
     * 工作台是点击即时合成（无合成过程），不存在进度显示——禁用进度槽占位。
     */
    @Override
    protected int progressSlot() {
        return -1;
    }

    @Override
    protected void tick(Block b) {
        // 工作台不自动合成；preRegister 未注册 ticker，本方法不会被调度
    }

    private void craft(BlockMenu menu) {
        // 顺序很关键：先无消耗匹配，命中后才扣能量并消耗输入、产出。
        //  · 若先扣能量再匹配：玩家空点击会白扣机器能量（可被反复刷空共享机器的能量）；
        //  · 若先消耗输入再扣能量：没电时已消耗输入却无产出（吞物品）。
        // 拆出无消耗 findMatch 后：无配方→不扣能量不消耗；有配方没电→不消耗不产出（安全失败）。
        // energy: false 的纯工作台不扣电。
        var m = findMatch(menu);
        if (m == null) return;
        if (usesEnergy && !takeCharge(menu.getLocation())) return;
        consumeMatch(menu, m);
        m.recipe.pushOutputs(menu, getOutputSlots());
    }
}
