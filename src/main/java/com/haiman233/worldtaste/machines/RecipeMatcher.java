package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.util.RecipeMatch;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import java.util.Arrays;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 机器配方匹配引擎（静态参数化，供电力机器 {@link WTRecipeMachine} 与纯工作台
 * {@link WTWorkbench} 两族共用——原为 WTRecipeMachine 私有逻辑，工作台脱离 AContainer
 * 继承链后提取至此）。
 *
 * <p>匹配语义：绑定槽直查 / 无绑定 best-fit（余量最小优先）、SF-id 预筛（可选）、
 * 消耗前实时复验、实体桶宽松比较（见 {@link RecipeMatch}）、耐久工具匹配、输出空间预检、
 * 深层页面防竞态。</p>
 */
public final class RecipeMatcher {

    private RecipeMatcher() {}

    /** 匹配结果：命中的配方 + 各输入项选中的输入槽下标（在 inputSlots 数组中的位置）。 */
    public static final class Match {
        public final WTRecipe recipe;
        public final int[] chosen;
        Match(WTRecipe recipe, int[] chosen) { this.recipe = recipe; this.chosen = chosen; }
    }

    /** 「槽位 GUI 下标 → inputSlots 数组位置」查表（54 覆盖整个背包尺寸，-1=非输入槽）。 */
    public static int[] buildPosBySlot(int[] inputSlots) {
        int[] pos = new int[54];
        Arrays.fill(pos, -1);
        for (int s = 0; s < inputSlots.length; s++) {
            int slot = inputSlots[s];
            if (slot >= 0 && slot < pos.length) pos[slot] = s;
        }
        return pos;
    }

    /** 闸门：是否启用 SF-id 预筛（仅当 ≥2 配方且所有非空输入均为已注册 SF 物品）。 */
    public static boolean computeSfPrune(List<WTRecipe> recipes) {
        if (recipes == null || recipes.size() < 2) return false;
        for (WTRecipe recipe : recipes) {
            ItemStack[] inputs = recipe.getInput();
            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != null && recipe.inputSfId(i) == null) return false;
            }
        }
        return true;
    }

    /** 在配方列表中匹配（仅匹配与校验，不消耗）。posBySlot = buildPosBySlot 的预计算结果。 */
    public static Match findMatch(BlockMenu inv, List<WTRecipe> recipeList,
                                  int[] inputSlots, int[] outputSlots,
                                  int[] posBySlot, boolean sfPrune) {
        int slotCount = inputSlots.length;
        ItemStack[] slotItems = new ItemStack[slotCount];
        for (int s = 0; s < slotCount; s++) {
            ItemStack it = inv.getItemInSlot(inputSlots[s]);
            slotItems[s] = (it == null) ? null : ItemStackWrapper.wrap(it);
        }
        // 所有输入槽为空时不可能命中任何配方：直接返回，避免空闲机器每 tick 白遍历
        boolean anyInput = false;
        for (ItemStack si : slotItems) {
            if (si != null) { anyInput = true; break; }
        }
        if (!anyInput) return null;

        String[] slotSfId = sfPrune ? resolveSlotSfIds(slotItems) : null;
        // 本配方匹配过程中已被占用的输入槽（保守禁止共享，防止消耗越过实际存量）
        boolean[] claimed = new boolean[slotCount];
        for (WTRecipe recipe : recipeList) {
            ItemStack[] inputs = recipe.getInput();
            int n = inputs.length;
            int[] chosen = new int[n];
            int matched = 0;
            boolean failed = false;
            Arrays.fill(claimed, false);
            for (int i = 0; i < n; i++) {
                chosen[i] = -1;
                ItemStack need = inputs[i];
                if (need == null) { matched++; continue; }
                int bound = recipe.inSlot(i);
                if (bound >= 0) {
                    // 绑定到指定槽：仅检查该槽（posBySlot 覆盖 0..53 GUI 槽；越界或非输入槽 → -1 → failed）
                    int pos = (bound < posBySlot.length) ? posBySlot[bound] : -1;
                    if (pos < 0 || claimed[pos]) { failed = true; break; }
                    ItemStack in = slotItems[pos];
                    if (in != null && in.getAmount() >= need.getAmount()
                            && !(sfPrune && idCertainlyMismatch(slotSfId[pos], recipe.inputSfId(i)))
                            && RecipeMatch.input(in, need, recipe.inputDamage(i) > 0)) {
                        chosen[i] = pos;
                        claimed[pos] = true;
                        matched++;
                    } else { failed = true; break; }
                } else {
                    // 无绑定输入：best-fit（余量最小优先），把大堆留给需要更多的输入项
                    int best = -1;
                    int bestAmount = Integer.MAX_VALUE;
                    for (int s = 0; s < slotCount; s++) {
                        if (claimed[s]) continue;
                        ItemStack in = slotItems[s];
                        if (in == null || in.getAmount() < need.getAmount()
                                || (sfPrune && idCertainlyMismatch(slotSfId[s], recipe.inputSfId(i)))
                                || !RecipeMatch.input(in, need, recipe.inputDamage(i) > 0)) {
                            continue;
                        }
                        if (in.getAmount() < bestAmount) {
                            best = s;
                            bestAmount = in.getAmount();
                            if (bestAmount == need.getAmount()) break;
                        }
                    }
                    if (best >= 0) {
                        chosen[i] = best;
                        claimed[best] = true;
                        matched++;
                    } else { failed = true; break; }
                }
            }
            if (failed || matched != n) continue;
            int distinct = 0;
            for (int i = 0; i < n; i++) {
                boolean dup = false;
                for (int j = 0; j < i; j++) if (chosen[i] == chosen[j]) { dup = true; break; }
                if (!dup) distinct++;
            }
            if (distinct != n) continue;
            // 输出放不下时跳过本配方尝试下一个
            if (!InvUtils.fitAll(inv.toInventory(), recipe.getOutput(), outputSlots)) continue;
            // 消耗前对选中槽位的实时内容再校验，避免竞态吞错物品
            boolean stillValid = true;
            for (int i = 0; i < n; i++) {
                if (recipe.isNoConsume(i) || chosen[i] < 0) continue;
                ItemStack live = inv.getItemInSlot(inputSlots[chosen[i]]);
                ItemStack need = inputs[i];
                if (live == null || live.getAmount() < need.getAmount()
                        || !RecipeMatch.input(live, need, recipe.inputDamage(i) > 0)) {
                    stillValid = false;
                    break;
                }
            }
            if (!stillValid) continue;
            return new Match(recipe, chosen);
        }
        return null;
    }

    /** 消耗已匹配配方的输入（跳过 noConsume 项；damage>0 的工具类输入按耐久损耗）。 */
    public static void consumeMatch(BlockMenu inv, Match m, int[] inputSlots) {
        if (m == null) return;
        ItemStack[] inputs = m.recipe.getInput();
        for (int i = 0; i < inputs.length; i++) {
            if (m.recipe.isNoConsume(i) || m.chosen[i] < 0) continue;
            int damage = m.recipe.inputDamage(i);
            if (damage > 0) {
                damageTool(inv, inputSlots[m.chosen[i]], damage);
            } else {
                inv.consumeItem(inputSlots[m.chosen[i]], inputs[i].getAmount());
            }
        }
    }

    /** 预解析各非空输入槽的 SF id（读 PDC）。仅 sfPrune=true 时使用。 */
    private static String[] resolveSlotSfIds(ItemStack[] slotItems) {
        String[] ids = new String[slotItems.length];
        for (int s = 0; s < slotItems.length; s++) {
            ItemStack it = slotItems[s];
            if (it != null && it.hasItemMeta()) {
                ids[s] = Slimefun.getItemDataService().getItemData(it.getItemMeta()).orElse(null);
            }
        }
        return ids;
    }

    /** 廉价必要条件预筛：两端均为已解析 SF 物品且 id 不同 → 必不匹配，可跳过 isItemSimilar。 */
    private static boolean idCertainlyMismatch(String inId, String needId) {
        return inId != null && needId != null && !inId.equals(needId);
    }

    /**
     * 工具类输入按耐久消耗：每次合成扣 damage 点耐久而不消耗物品本身。
     * 耐久耗尽时工具损坏消失；不可损耗的物品既不消耗也不损耗（工具是长期驻留的生产资料）。
     */
    private static void damageTool(BlockMenu inv, int slot, int damage) {
        ItemStack item = inv.getItemInSlot(slot);
        if (item == null || item.getType().isAir()) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable damageable)
                || item.getType().getMaxDurability() <= 0 || meta.isUnbreakable()) {
            return;
        }
        int newDamage = damageable.getDamage() + damage;
        if (newDamage >= item.getType().getMaxDurability()) {
            inv.replaceExistingItem(slot, null);
            return;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = item.clone();
        updated.setItemMeta(damageable);
        inv.replaceExistingItem(slot, updated);
    }
}
