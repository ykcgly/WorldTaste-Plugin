package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 工作台（workbenches.yml，如河豚工作台）：点击 click 槽位即时合成——不耗电、无进度、
 * 不参与粘液 tick（1.10.0 起，海曼书皮压缩机模式：extends {@link SlimefunItem} 而非
 * AContainer，从根源脱离电力组件与 tick 调度）。
 *
 * <p>需要耗电的工作台（如百味万用炉）见子类 {@link WTEnergyWorkbench}
 * （yml {@code energy: true}，默认）。</p>
 *
 * <p>匹配/消耗/菜单布局与电力机器同源：{@link RecipeMatcher}（静态匹配引擎）+
 * {@link MachineMenuBuilder}（静态菜单构建）+ {@link RecipeMatch}（实体桶/工具比较）。</p>
 */
public class WTWorkbench extends SlimefunItem implements InventoryBlock, WTMachine {

    private final int clickSlot;
    private final int[] inputSlots;
    private final int[] outputSlots;
    private final int[] posBySlot;
    private final List<WTRecipe> recipes;
    private final MenuDef menu;
    private final Map<Location, WTRecipe> lastFilled = new ConcurrentHashMap<>();

    public WTWorkbench(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                       int[] input, int[] output, List<WTRecipe> recipes,
                       int capacity, int consumption, int speed, MenuDef menu, boolean hideAll,
                       int clickSlot, boolean usesEnergy) {
        super(group, item, rt, recipe);
        this.inputSlots = input;
        this.outputSlots = output;
        this.posBySlot = RecipeMatcher.buildPosBySlot(input);
        this.recipes = recipes;
        this.menu = menu;
        this.clickSlot = clickSlot;
        // capacity/consumption/usesEnergy 对纯工作台均无意义（免电免 tick）；
        // 耗电变体 WTEnergyWorkbench 自行取用
        addItemHandler(new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), inputSlots);
                    inv.dropItems(b.getLocation(), outputSlots);
                }
            }
        });
        // 机器登记（大配方展示/产物反查/禁用配方过滤共用 INSTANCES）
        WTRecipeMachine.registerMachine(this);
        // 必须用显式 preset（覆写 newInstance 挂点击合成）——
        // InventoryBlock.createPreset 的匿名类不覆写 newInstance（fork 默认空实现），
        // 会导致点击合成 handler 从未注册（实测：河豚工作台点合成无反应）
        createWorkbenchPreset();
    }

    private void constructMenu(BlockMenuPreset preset) {
        MachineMenuBuilder.build(preset, this, menu, inputSlots, outputSlots, -1, null, java.util.Collections.emptySet());
    }

    @Override
    public int[] getInputSlots() { return inputSlots; }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    @Override
    public List<WTRecipe> getRecipes() { return recipes; }

    @Override
    public void rememberFilled(Location loc, WTRecipe recipe) {
        if (loc != null && recipe != null) lastFilled.put(loc, recipe);
    }

    @Override
    public WTRecipe lastFilledAt(Location loc) {
        return loc == null ? null : lastFilled.get(loc);
    }

    private void craft(BlockMenu menu) {
        // 顺序很关键：先无消耗匹配，命中后才消耗输入、产出（纯工作台不扣电）。
        var m = RecipeMatcher.findMatch(menu, recipes, inputSlots, outputSlots, posBySlot, false);
        if (m == null) return;
        if (!consumeEnergy(menu)) return;
        RecipeMatcher.consumeMatch(menu, m, inputSlots);
        m.recipe.pushOutputs(menu, outputSlots);
    }

    /** 能量钩子：纯工作台恒 true（不扣电）；耗电变体（WTEnergyWorkbench）覆盖为扣电语义。 */
    protected boolean consumeEnergy(BlockMenu menu) {
        return true;
    }

    /** 创建机器界面 preset（构造器内调用）：挂点击合成与 cargo 传输。 */
    protected BlockMenuPreset createWorkbenchPreset() {
        return new BlockMenuPreset(getId(), getItemName()) {
            @Override
            public void init() {
                constructMenu(this);
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                menu.addMenuClickHandler(clickSlot, (player, slot, clicked, action) -> {
                    craft(menu);
                    return false;
                });
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return flow == ItemTransportFlow.INSERT ? inputSlots : outputSlots;
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
}
