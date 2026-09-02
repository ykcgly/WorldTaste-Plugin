package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * 电力配方机器（recipe_machines.yml）。继承 {@link AContainer} 复用能量/进度/GUI 基建，
 * 自定义 {@link #findNextRecipe} 与 {@link #tick} 以支持 {@link WTRecipe} 的概率产出与 noConsume。
 */
public class WTRecipeMachine extends AContainer implements RecipeDisplayItem {

    private final int[] inputSlots;
    private final int[] outputSlots;
    /** 输入槽 GUI 索引 → 在 inputSlots 数组中的位置（-1=非输入槽）。仅依赖 inputSlots（不变量），构造期一次预算，
     *  避免每 tick 在 findMatch 里 new HashMap + Integer 装箱（输出阻塞机器每 tick 触发，分配压力可观）。 */
    private final int[] posBySlot;
    private final List<WTRecipe> recipes;
    private final MenuDef menu;
    private final boolean hideAll;
    private final ItemStack progressBar;
    /** 是否启用 SF-id 预筛（仅纯-SF 机器为 true）。见 {@link #computeSfPrune}。 */
    private final boolean sfPrune;
    /** 进行中配方（按方块），完成时取此处的 WTRecipe 做概率滚动。 */
    private final Map<org.bukkit.Location, WTRecipe> active = new ConcurrentHashMap<>();

    public WTRecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                           int[] inputSlots, int[] outputSlots, List<WTRecipe> recipes,
                           int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.posBySlot = buildPosBySlot(inputSlots);
        this.recipes = recipes;
        this.sfPrune = computeSfPrune(recipes);
        this.menu = menu;
        this.hideAll = hideAll;
        // 默认进度条用打火石（可损坏物品）：Slimefun 的 updateProgressbar 会以耐久条 + 进度百分比 + 剩余
        // 时间呈现，比静态玻璃板更醒目（对齐 Slimefun 本体电力机器 ElectricSmeltery 等）。菜单配置的
        // progressbar 物品仍优先（menus.yml 每机器可覆盖）。
        this.progressBar = (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
        setProcessingSpeed(Math.max(1, speed));
        setCapacity(Math.max(1, capacity));
        setEnergyConsumption(Math.max(1, Math.min(consumption, Math.max(1, capacity))));
        // AContainer 在 super() 中已用 this::constructMenu 建过 preset（此时字段尚未赋值），
        // 这里字段就绪后重建 preset（覆盖前一个），并补设进度条。
        createPreset(this, getInventoryTitle(), this::constructMenu);
        getMachineProcessor().setProgressBar(progressBar);
    }

    @Override
    public int[] getInputSlots() { return inputSlots; }

    /** 全部配方（供大配方菜单展示）。 */
    public List<WTRecipe> getRecipes() { return recipes; }

    /** 校验当前机器输入能否匹配任一配方（配方补全后的验证，不消耗）。 */
    public boolean canMatch(BlockMenu inv) {
        return findMatch(inv) != null;
    }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    @Override
    public String getMachineIdentifier() { return getId(); }

    @Override
    public ItemStack getProgressBar() { return progressBar; }

    @Override
    protected void registerDefaultRecipes() { /* 配方由本类自行管理 */ }

    private int progressSlot() {
        return (menu != null && menu.progressSlot >= 0) ? menu.progressSlot : 22;
    }

    /** 额外的可交互槽位（不会被背景填充阻挡），子类（如模板机器）可覆盖以加入模板槽等。 */
    protected java.util.Set<Integer> extraFunctionalSlots() {
        return java.util.Collections.emptySet();
    }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // super() 阶段会提前调用一次（字段为 null），此时跳过；由构造器末尾重建 preset 时再真正构建。
        if (inputSlots == null || outputSlots == null) return;
        Set<Integer> placed = new HashSet<>();
        // 装饰（来自菜单）
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }
        int pslot = progressSlot();
        // 功能槽
        Set<Integer> functional = new HashSet<>();
        for (int s : inputSlots) functional.add(s);
        for (int s : outputSlots) functional.add(s);
        if (pslot >= 0) functional.add(pslot);
        // 背景填充剩余槽位
        java.util.Set<Integer> extra = extraFunctionalSlots();
        // 尺寸必须覆盖所有功能槽(input/output/progress/extra)与装饰槽：
        // BlockMenuPreset 按“已放置物品”自动定尺寸，而 output 槽只挂 click handler、不放置物品。
        // 若某功能槽超出自动尺寸(如机器无对应菜单、或菜单装饰未覆盖该槽)，运行期
        // getItemInSlot/consumeItem 会越界。此处按最大槽位向上取整到 9 的倍数(且 ≤54 背包上限)。
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        for (int s : inputSlots) maxSlot = Math.max(maxSlot, s);
        for (int s : outputSlots) maxSlot = Math.max(maxSlot, s);
        if (pslot >= 0) maxSlot = Math.max(maxSlot, pslot);
        for (int s : extra) maxSlot = Math.max(maxSlot, s);
        for (int s : placed) maxSlot = Math.max(maxSlot, s);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        for (int i = 0; i < size; i++) {
            if (!functional.contains(i) && !placed.contains(i) && !extra.contains(i)) {
                preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
            }
        }
        // 配方补全按钮（自定义：可完整选择机器工作配方；JEG 配方补全仅按物品展示配方，大型配方不完整）
        int fillSlot = -1;
        for (int s : new int[]{53, 52, 8, 17, 26, 35, 44, 7, 16, 25, 43, 51, 0}) {
            if (!functional.contains(s) && !placed.contains(s) && !extra.contains(s)) { fillSlot = s; break; }
        }
        if (fillSlot < 0) {
            for (int s2 = 0; s2 < 54; s2++) {
                if (!functional.contains(s2) && !placed.contains(s2) && !extra.contains(s2)) { fillSlot = s2; break; }
            }

        // GUI 全被功能/装饰占满（如恒温陈酿皿 54 格全满）：覆盖一个边缘装饰槽（仅装饰显示，不影响功能）
        if (fillSlot < 0) {
            for (int s3 : new int[]{53, 52, 8, 17, 26, 35, 44, 43, 51, 0}) {
                if (placed.contains(s3) && !functional.contains(s3) && !extra.contains(s3)) { fillSlot = s3; break; }
            }
        }
        }
        // 配方补全按钮：所有配方机器显示。小型机器原用 JEG 配方补全书，但 JEG Build 205
        // 对绑定槽配方存在循环左移缺陷（填充顺序与 GUI 布局不一致），统一改用自定义精确补全
        if (fillSlot >= 0) {
            preset.addItem(fillSlot, fillButton(), (player, s, cursor, action) -> {
                me.mrCookieSlime.Slimefun.api.inventory.BlockMenu bm = null;
                if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                    org.bukkit.inventory.InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                    if (holder instanceof me.mrCookieSlime.Slimefun.api.inventory.BlockMenu blockMenu) {
                        bm = blockMenu;
                    }
                }
                com.haiman233.worldtaste.guide.RecipeFillMenu.open(player, WTRecipeMachine.this, bm);
                return false;
            });
        }

        // 进度占位
        if (pslot >= 0 && !placed.contains(pslot)) {
            preset.addItem(pslot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
        // 输出槽允许取出
        for (int i : outputSlots) {
            // 输出槽：允许取出（左键提起 / shift 批量取出）、禁止放入。
            // 必须用 AdvancedMenuClickHandler：普通 MenuClickHandler 的第三参数是 e.getCurrentItem()
            // （槽内物品），左键提起会被误判为放入而取消；Advanced 版本才能拿到玩家光标
            // e.getCursor()（空手点击 = AIR → 放行提起）。
            preset.addMenuClickHandler(i, new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, org.bukkit.entity.Player player,
                                       int slot, org.bukkit.inventory.ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }

                @Override
                public boolean onClick(org.bukkit.entity.Player player, int slot, org.bukkit.inventory.ItemStack cursor,
                                       me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                    return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
                }
            });
        }
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), inputSlots);
                    inv.dropItems(b.getLocation(), outputSlots);
                    // 额外功能槽（如模板机器的模板槽）内容物也掉落，避免被吞
                    java.util.Set<Integer> extra = extraFunctionalSlots();
                    if (!extra.isEmpty()) {
                        int[] extraSlots = extra.stream().mapToInt(Integer::intValue).toArray();
                        inv.dropItems(b.getLocation(), extraSlots);
                    }
                }
                active.remove(b.getLocation());
                getMachineProcessor().endOperation(b);
            }
        };
    }

    @Override
    protected void tick(Block b) {
        BlockMenu inv = BlockStorage.getInventory(b.getLocation());
        if (inv == null) return;
        int pslot = progressSlot();
        CraftingOperation op = getMachineProcessor().getOperation(b);
        if (op == null) {
            MachineRecipe next = findNextRecipe(inv);
            if (next instanceof WTRecipe) {
                op = new CraftingOperation(next);
                getMachineProcessor().startOperation(b, op);
                active.put(b.getLocation(), (WTRecipe) next);
                getMachineProcessor().updateProgressBar(inv, pslot, op);
            }
            return;
        }
        if (!takeCharge(b.getLocation())) return;
        if (!op.isFinished()) {
            getMachineProcessor().updateProgressBar(inv, pslot, op);
            op.addProgress(getSpeed());
            return;
        }
        WTRecipe r = active.remove(b.getLocation());
        if (r != null) {
            pushRecipeOutputs(b, inv, r);
        }
        inv.replaceExistingItem(pslot, progressBar);
        getMachineProcessor().endOperation(b);
    }

    @Override
    protected MachineRecipe findNextRecipe(BlockMenu inv) {
        return matchRecipes(inv, recipes);
    }

    /**
     * 把已完成配方的产出推入机器（默认推入 outputSlots）。抽取为可覆盖钩子，供子类（如
     * {@link WTTemplateMachine} 的 {@code moreOutputIfMoreTemplates}）按需放大产出。
     */
    protected void pushRecipeOutputs(Block b, BlockMenu inv, WTRecipe r) {
        r.pushOutputs(inv, outputSlots);
    }

    /** 匹配结果：命中的配方 + 各输入项选中的输入槽下标（在 {@link #inputSlots} 中的位置）。消耗前保持有效。 */
    protected static final class Match {
        final WTRecipe recipe;
        final int[] chosen;
        Match(WTRecipe recipe, int[] chosen) { this.recipe = recipe; this.chosen = chosen; }
    }

    /** 在给定配方列表中匹配输入（供模板机器按当前模板筛选后复用）。仅匹配与校验，不消耗输入。 */
    protected Match findMatch(BlockMenu inv, List<WTRecipe> recipeList) {
        int[] slots = inputSlots;
        int slotCount = slots.length;
        ItemStack[] slotItems = new ItemStack[slotCount];
        // posOf 已在构造期预算为 posBySlot（不变量），此处不再每 tick 重建 HashMap。
        for (int s = 0; s < slotCount; s++) {
            ItemStack it = inv.getItemInSlot(slots[s]);
            slotItems[s] = (it == null) ? null : ItemStackWrapper.wrap(it);
        }
        // 所有输入槽为空时不可能命中任何配方（注册配方至少含 1 个非空输入）：
        // 直接返回，避免空闲机器每 tick 白遍历全部配方（含昂贵的 isItemSimilar，高负载下显著省 TPS）。
        boolean anyInput = false;
        for (ItemStack si : slotItems) {
            if (si != null) { anyInput = true; break; }
        }
        if (!anyInput) return null;
        // SF-id 预筛（仅纯-SF 机器启用，见 sfPrune）：每 tick 对每个非空输入槽解析一次 SF id（读 PDC），
        // 随后用廉价必要条件 idCertainlyMismatch 跳过「两端均 SF 且 id 不同」者——此类 isItemSimilar 必返回
        // false（其 both-SF 分支按 id 比较），故跳过不改变匹配结果，仅省去昂贵的 isItemSimilar（内含 2× getByItem）。
        // sfPrune=false 时 slotSfId=null，下方 !(sfPrune && ...) 短路为 true，行为与优化前逐字一致（零回归）。
        String[] slotSfId = sfPrune ? resolveSlotSfIds(slotItems) : null;
        // 本配方匹配过程中已被占用的输入槽（同一物理槽不允许被多个配方位消耗，除非总量足够——
        // 现实现保守禁止共享，防止消耗越过实际存量导致吞物品）。
        boolean[] claimed = new boolean[slotCount];
        for (WTRecipe recipe : recipeList) {
            ItemStack[] inputs = recipe.getInput();
            int n = inputs.length;
            int[] chosen = new int[n];
            int matched = 0;
            boolean failed = false;
            java.util.Arrays.fill(claimed, false);
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
                            && (recipe.inputDamage(i) > 0
                                    ? toolSimilarIgnoreDamage(in, need)
                                    : SlimefunUtils.isItemSimilar(in, need, true))) {
                        chosen[i] = pos;
                        claimed[pos] = true;
                        matched++;
                    } else { failed = true; break; }
                } else {
                    // 无绑定输入：在所有可满足且未被本配方其它项占用的槽中选「余量最小」的（best-fit），
                    // 把大堆留给需要更多材料的输入项——修复同物品多条输入（如 [A×1, A×2]）在
                    // 两条输入都贪心命中同一槽后被 distinct 去重整条误杀的问题
                    int best = -1;
                    int bestAmount = Integer.MAX_VALUE;
                    for (int s = 0; s < slotCount; s++) {
                        if (claimed[s]) continue;
                        ItemStack in = slotItems[s];
                        if (in == null || in.getAmount() < need.getAmount()
                                || (sfPrune && idCertainlyMismatch(slotSfId[s], recipe.inputSfId(i)))
                                || !(recipe.inputDamage(i) > 0
                                        ? toolSimilarIgnoreDamage(in, need)
                                        : SlimefunUtils.isItemSimilar(in, need, true))) {
                            continue;
                        }
                        if (in.getAmount() < bestAmount) {
                            best = s;
                            bestAmount = in.getAmount();
                            if (bestAmount == need.getAmount()) break; // 完全贴合，不可能更小
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
            // 输出放不下时跳过本配方尝试下一个（而非整体放弃）：不同配方的输出项可能不同，
            // 某项输出放不下不应阻塞输出项不同的其它可合成配方。
            if (!InvUtils.fitAll(inv.toInventory(), recipe.getOutput(), outputSlots)) continue;
            // tick 可能异步执行：匹配用的是快照，消耗前对选中槽位的实时内容再校验，避免竞态吞错物品
            boolean stillValid = true;
            for (int i = 0; i < n; i++) {
                if (recipe.isNoConsume(i) || chosen[i] < 0) continue;
                ItemStack live = inv.getItemInSlot(slots[chosen[i]]);
                ItemStack need = inputs[i];
                if (live == null || live.getAmount() < need.getAmount()
                        || !(recipe.inputDamage(i) > 0
                                ? toolSimilarIgnoreDamage(live, need)
                                : SlimefunUtils.isItemSimilar(live, need, true))) {
                    stillValid = false;
                    break;
                }
            }
            if (!stillValid) continue;
            return new Match(recipe, chosen);
        }
        return null;
    }

    /**
     * 耐久类工具输入比较：忽略损耗值（受损钓竿仍匹配新钓竿模板）。
     * 同材质（粘液物品按 id 一致）即匹配；模板带显示名时要求名称一致。
     */
    private static boolean toolSimilarIgnoreDamage(ItemStack in, ItemStack need) {
        if (in == null || in.getType() != need.getType()) return false;
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfNeed =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfIn =
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(in);
            return sfIn != null && sfIn.getId().equals(sfNeed.getId());
        }
        if (need.hasItemMeta() && need.getItemMeta().hasDisplayName()) {
            org.bukkit.inventory.meta.ItemMeta m = in.getItemMeta();
            return m != null && m.hasDisplayName()
                    && m.getDisplayName().equals(need.getItemMeta().getDisplayName());
        }
        return true;
    }
    /** 用本机器的全部配方匹配（不消耗）。 */
    protected Match findMatch(BlockMenu inv) {
        return findMatch(inv, recipes);
    }

    /** 构造期预算 inputSlots 的「GUI 槽 → 数组位置」查表（54 覆盖整个背包尺寸，-1=非输入槽）。 */
    private static int[] buildPosBySlot(int[] inputSlots) {
        int[] pos = new int[54];
        java.util.Arrays.fill(pos, -1);
        for (int s = 0; s < inputSlots.length; s++) {
            int slot = inputSlots[s];
            if (slot >= 0 && slot < pos.length) pos[slot] = s;
        }
        return pos;
    }

    /** 预解析各非空输入槽的 SF id（读 PDC，每 tick 每槽一次）。仅 sfPrune=true 时调用。 */
    private static String[] resolveSlotSfIds(ItemStack[] slotItems) {
        String[] ids = new String[slotItems.length];
        for (int s = 0; s < slotItems.length; s++) {
            ItemStack it = slotItems[s];
            if (it != null && it.hasItemMeta()) {
                ids[s] = io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getItemDataService()
                        .getItemData(it.getItemMeta()).orElse(null);
            }
        }
        return ids;
    }

    /**
     * 廉价必要条件预筛：两端均为已解析 SF 物品且 id 不同时返回 true（即「确定不匹配」，可安全跳过 isItemSimilar）。
     * <p>安全性依据 {@link SlimefunUtils#isItemSimilar}：当 item 与 sfitem 均为已注册 SF 物品且 id 不同时，
     * 其 both-SF 分支（REF SlimefunUtils.java:363-366）必返回 false。其余情形（任一为原版、id 相同、或无 PDC）
     * 一律返回 false（不跳过），仍交 isItemSimilar 定夺，完整保留 DistinctiveItem/meta 等边界语义与 RSC 保真度。
     */
    private static boolean idCertainlyMismatch(String inId, String needId) {
        return inId != null && needId != null && !inId.equals(needId);
    }

    /**
     * 闸门：是否启用 SF-id 预筛。仅当「≥2 配方 且 所有配方的所有非空输入均为已注册 SF 物品」时为 true。
     * <p>纯-SF 机器（如头颅类）的每 tick 扫描代价由 getByItem 主导，预筛可将其从 O(配方数) 次昂贵比较降至
     * O(命中) 次；而原版/混合机器的代价由廉价的类型短路主导，预筛无收益反增解析开销，故关闭（零回归）。
     */
    private static boolean computeSfPrune(List<WTRecipe> recipes) {
        if (recipes == null || recipes.size() < 2) return false;
        for (WTRecipe recipe : recipes) {
            ItemStack[] inputs = recipe.getInput();
            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != null && recipe.inputSfId(i) == null) return false;
            }
        }
        return true;
    }

    /** 消耗已匹配配方的输入（跳过 noConsume 项与未占用槽位；damage&gt;0 的工具类输入按耐久损耗）。 */
    protected void consumeMatch(BlockMenu inv, Match m) {
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

    /**
     * 工具类输入按耐久消耗：每次合成扣 damage 点耐久而不消耗物品本身（如捕鱼野猎网的各类钓竿）。
     * 耐久耗尽时工具损坏消失；不可损耗（无耐久上限/无法破坏）的物品既不消耗也不损耗，避免吞工具。
     * 不可按耐久损耗的物品缺省语义为「不消耗」而非「整件消耗」——工具在机器内是长期驻留的生产资料。
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
            // 耐久耗尽：工具损坏消失
            inv.replaceExistingItem(slot, null);
            return;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = item.clone();
        updated.setItemMeta(damageable);
        inv.replaceExistingItem(slot, updated);
    }

    /** 匹配并消耗输入（tick 路径：操作会在机器内暂存，没电也不会丢输入）。 */
    protected MachineRecipe matchRecipes(BlockMenu inv, List<WTRecipe> recipeList) {
        Match m = findMatch(inv, recipeList);
        if (m == null) return null;
        consumeMatch(inv, m);
        return m.recipe;
    }

    /** 指南展示配方缓存。recipes 在构造后不变，展示列表亦不变，首次调用预算并缓存（同 R2「提升不变量」原则：
     *  避免每次打开指南都 new ArrayList + 重建）。 */
    private List<ItemStack> displayRecipesCache;

    @Override
    public List<ItemStack> getDisplayRecipes() {
        if (displayRecipesCache != null) return displayRecipesCache;
        List<ItemStack> out = new ArrayList<>();
        if (!hideAll) {
            for (WTRecipe r : recipes) {
                ItemStack[] in = r.getInput();
                ItemStack[] res = r.getOutput();
                ItemStack primaryOut = (res.length > 0 && res[0] != null)
                        ? res[0] : new ItemStack(Material.BARRIER);
                boolean any = false;
                // 大型配方完整展示（对齐 LogiTech RecipeDisplay）：每个非空输入生成 [材料 → 产物] 对
                for (ItemStack i : in) {
                    if (i != null) {
                        out.add(i.clone());
                        out.add(describeOutput(primaryOut, r));
                        any = true;
                    }
                }
                if (!any) {
                    out.add(new ItemStack(Material.BARRIER));
                    out.add(describeOutput(primaryOut, r));
                }
            }
        }
        displayRecipesCache = out;
        return out;
    }

    /** 展示副本：克隆输出并附加耗时 lore（仅展示用，不影响实际产出）。 */

    /** 配方补全按钮。 */
    private static ItemStack fillButton() {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BOOK);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GREEN + "配方补全");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "选择完整配方后自动从背包填充材料");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack describeOutput(ItemStack out, WTRecipe r) {
        ItemStack clone = out.clone();
        org.bukkit.inventory.meta.ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            java.util.List<String> lore = meta.getLore() != null
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(org.bukkit.ChatColor.GRAY + "耗时: " + (r.getTicks() / 2) + "s");
            meta.setLore(lore);
            clone.setItemMeta(meta);
        }
        return clone;
    }
}
