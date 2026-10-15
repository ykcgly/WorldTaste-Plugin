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
 * 工作台（workbenches.yml，如百味万用炉）：复用 {@link WTRecipeMachine} 的配方/能量/GUI，
 * 但不自动 tick 合成；改为玩家点击 click 槽位时即时合成（消耗能量、匹配并消耗输入、产出）。
 */
public class WTWorkbench extends WTRecipeMachine {

    private final int clickSlot;

    public WTWorkbench(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                       int[] input, int[] output, List<WTRecipe> recipes,
                       int capacity, int consumption, int speed, MenuDef menu, boolean hideAll, int clickSlot) {
        super(group, item, rt, recipe, input, output, recipes, capacity, consumption, speed, menu, hideAll);
        this.clickSlot = clickSlot;
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

    @Override
    protected void tick(Block b) {
        // 工作台不自动合成，仅在点击 click 槽时合成
    }

    /**
     * 工作台是点击即时合成（无合成过程），不存在进度显示——禁用进度槽占位。
     * 修复河豚工作台实测问题：其 6×5 合成格输入槽覆盖默认进度槽 22，
     * 父类默认的打火石进度占位会叠进输入格，成为「不明来路的打火石」。
     */
    @Override
    protected int progressSlot() {
        return -1;
    }

    private void craft(BlockMenu menu) {
        // 顺序很关键：先无消耗匹配，命中后才扣能量并消耗输入、产出。
        //  · 若先扣能量再匹配：玩家空点击会白扣机器能量（可被反复刷空共享机器的能量）；
        //  · 若先消耗输入再扣能量：没电时已消耗输入却无产出（吞物品）。
        // 拆出无消耗 findMatch 后：无配方→不扣能量不消耗；有配方没电→不消耗不产出（安全失败）。
        var m = findMatch(menu);
        if (m == null) return;
        if (!takeCharge(menu.getLocation())) return;
        consumeMatch(menu, m);
        m.recipe.pushOutputs(menu, getOutputSlots());
    }
}
