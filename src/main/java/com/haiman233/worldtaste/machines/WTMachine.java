package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * 尘世百味「配方类机器」通用接口：统一电力机器（{@link WTRecipeMachine}，继承 AContainer）
 * 与纯工作台（{@link WTWorkbench}，海曼模式 extends SlimefunItem）两族的对外能力，
 * 供配方补全（RecipeFillMenu）、大配方展示（BigRecipeMenu）、JEG 事件桥等按接口编程。
 *
 * <p>继承 {@code SlimefunItem} 的成员（getId/getItemName/getItem/getRecipe/getRecipeType）
 * 由两类自动满足；本接口只额外声明槽位/配方/补全记忆三类能力。</p>
 */
public interface WTMachine {

    /** 输入槽（GUI 槽位数组）。 */
    int[] getInputSlots();

    /** 输出槽（GUI 槽位数组）。 */
    int[] getOutputSlots();

    /** 全部工作配方（供大配方展示与补全列表）。 */
    List<WTRecipe> getRecipes();

    /** 记录该方块机器「上一次补全的配方」（补全界面填充时调用）。 */
    void rememberFilled(Location loc, WTRecipe recipe);

    /** 读取该方块机器「上一次补全的配方」；从未补全过返回 null。 */
    WTRecipe lastFilledAt(Location loc);

    /** 机器显示名（指南/提示用；SlimefunItem#getItemName）。 */
    String getItemName();

    /** 机器本体物品（大配方页右上角图标；SlimefunItem#getItem）。 */
    ItemStack getItem();

    /** 机器自身的合成配方（大配方页第 0 页；SlimefunItem#getRecipe）。 */
    ItemStack[] getRecipe();

    /** 机器配方类型（大配方页图标；SlimefunItem#getRecipeType）。 */
    RecipeType getRecipeType();

    /**
     * 该方块是否属于本机器（配方补全/返回键的界面归属校验）。
     * 默认按 BlockMenuPreset 的注册物品比对。
     */
    default boolean owns(BlockMenu menu) {
        return menu != null && menu.getPreset() != null && menu.getPreset().getSlimefunItem() == this;
    }

    /** 物品传输槽位（cargo 支持；默认输入进/输出出）。 */
    default int[] getSlotsAccessedByItemTransport(BlockMenuPreset preset, ItemTransportFlow flow) {
        return flow == ItemTransportFlow.INSERT ? getInputSlots() : getOutputSlots();
    }
}
