package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 *
 * <p>1.10.0 起：匹配引擎提取至 {@link RecipeMatcher}、菜单构建提取至 {@link MachineMenuBuilder}
 * （供纯工作台 {@link WTWorkbench} 复用）；本类实现 {@link WTMachine} 通用接口——
 * 配方补全/大配方展示/JEG 事件桥均按接口编程，不再依赖本具体类型。</p>
 */
public class WTRecipeMachine extends AContainer implements RecipeDisplayItem, WTMachine {

    /** 匹配结果类型别名（引擎在 {@link RecipeMatcher#Match}）。 */


    private final int[] inputSlots;
    private final int[] outputSlots;
    /** 输入槽 GUI 索引 → 在 inputSlots 数组中的位置（-1=非输入槽）。仅依赖 inputSlots（不变量），构造期一次预算，
     *  避免每 tick 在 findMatch 里 new HashMap + Integer 装箱（输出阻塞机器每 tick 触发，分配压力可观）。 */
    private final int[] posBySlot;
    private final List<WTRecipe> recipes;
    private final MenuDef menu;
    private final boolean hideAll;
    private final ItemStack progressBar;
    /** 解析后的进度槽：menu 指定值或默认 22；与输入/输出槽冲突时为 -1（见构造器防御）。 */
    private final int progressSlotResolved;
    /** 是否启用 SF-id 预筛（仅纯-SF 机器为 true）。见 {@link RecipeMatcher#computeSfPrune}。 */
    private final boolean sfPrune;
    /** 进行中配方（按方块），完成时取此处的 WTRecipe 做概率滚动。 */
    private final Map<org.bukkit.Location, WTRecipe> active = new ConcurrentHashMap<>();
    /** 玩家在该方块机器上「上一次补全的配方」（按方块位置；补全界面填充时记录，供补全按钮右键一键复补）。 */
    private final Map<org.bukkit.Location, WTRecipe> lastFilled = new ConcurrentHashMap<>();
    /** 全部已注册实例（加载收尾统一剔除引用禁用物品的配方用；含 recipe_machines/linked/workbench/template）。 */
    private static final List<WTMachine> INSTANCES = new ArrayList<>();

    /**
     * 加载收尾调用（{@code Setup.loadAll} 末尾）：剔除引用了「未注册或已禁用」粘液物品的配方。
     *
     * <p>根因：{@code brewing.enabled=false} 时 {@code WTConfig.blockItem} 只拦物品本体的注册，
     * 但其它机器配方以 {@code material_type: slimefun} 引用这些 id 时，{@code Read.resolve} 会走
     * {@code WT.preload} 兜底包成带 id 的 SlimefunItemStack——配方照常注册、照常可合成、照常出现在
     * 大配方展示与配方补全里。此处按配方物品的 PDC id 统一复查：id 查不到注册项，或注册项
     * {@link SlimefunItem#isDisabled()}（覆盖 /sf disable 等运行期禁用）为 true → 整条配方剔除。
     * 合成匹配、大配方展示、配方补全三处共用 {@link #getRecipes()}，一并失效。</p>
     *
     * <p>跨附属缺失引用不经过 preload（解析为占位石头、无 id PDC），不受本过滤影响，行为不变。</p>
     */
    public static void filterDisabledRecipes() {
        int removed = 0, machines = 0;
        for (WTMachine m : INSTANCES) {
            List<WTRecipe> rs = m.getRecipes();
            if (rs == null || rs.isEmpty()) continue;
            machines++;
            int before = rs.size();
            rs.removeIf(WTRecipeMachine::referencesDisabledItem);
            removed += before - rs.size();
        }
        if (removed > 0) {
            WT.log("已剔除 " + removed + " 条引用未注册/禁用物品的机器配方（涉及 " + machines + " 台机器）");
        }
    }

    /** 配方的任一输入或输出引用了未注册/已禁用的粘液物品时返回 true（整条配方不可用）。 */
    private static boolean referencesDisabledItem(WTRecipe r) {
        for (ItemStack in : r.getInput()) if (isDisabledStack(in)) return true;
        for (ItemStack out : r.getOutput()) if (isDisabledStack(out)) return true;
        return false;
    }

    /** 物品带 slimefun id PDC 但该 id 未注册（ brewing 关闭的 preload 幽灵）或已禁用时返回 true。 */
    private static boolean isDisabledStack(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        String id = Slimefun.getItemDataService().getItemData(it.getItemMeta()).orElse(null);
        if (id == null) return false;
        SlimefunItem sf = SlimefunItem.getById(id);
        return sf == null || sf.isDisabled();
    }

    /** 登记机器实例（WTRecipeMachine 构造器自动调用；纯工作台等外部实现需手动调用）。 */
    public static void registerMachine(WTMachine machine) {
        INSTANCES.add(machine);
    }

    public WTRecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                           int[] inputSlots, int[] outputSlots, List<WTRecipe> recipes,
                           int capacity, int consumption, int speed, MenuDef menu, boolean hideAll) {
        super(group, item, rt, recipe);
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.posBySlot = RecipeMatcher.buildPosBySlot(inputSlots);
        this.recipes = recipes;
        this.sfPrune = RecipeMatcher.computeSfPrune(recipes);
        this.menu = menu;
        this.hideAll = hideAll;
        INSTANCES.add(this);
        // 默认进度条用打火石（可损坏物品）：Slimefun 的 updateProgressbar 会以耐久条 + 进度百分比 + 剩余
        // 时间呈现，比静态玻璃板更醒目（对齐 Slimefun 本体电力机器 ElectricSmeltery 等）。菜单配置的
        // progressbar 物品仍优先（menus.yml 每机器可覆盖）。
        this.progressBar = (menu != null && menu.progressItem != null)
                ? menu.progressItem : new ItemStack(Material.FLINT_AND_STEEL);
        // 进度槽解析：菜单漏配 progressbar 时回退默认 22，与输入/输出槽重叠时禁用并告警
        //（防占位物叠进功能槽；纯工作台已脱离本类，见 WTWorkbench）。
        int ps = (menu != null && menu.progressSlot >= 0) ? menu.progressSlot : 22;
        if (overlapsFunctional(ps)) {
            WT.log(getId() + " 的进度槽 " + ps + " 与输入/输出槽重叠（menus.yml 缺 progressbar 配置？），已禁用进度占位");
            ps = -1;
        }
        this.progressSlotResolved = ps;
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
    @Override
    public List<WTRecipe> getRecipes() { return recipes; }

    @Override
    public int[] getOutputSlots() { return outputSlots; }

    @Override
    public String getMachineIdentifier() { return getId(); }

    @Override
    public ItemStack getProgressBar() { return progressBar; }

    @Override
    protected void registerDefaultRecipes() { /* 配方由本类自行管理 */ }

    /** 进度槽解析结果（工作台恒 -1；子类可覆盖，返回 -1 表示本机器无进度显示）。 */
    protected int progressSlot() {
        return progressSlotResolved;
    }

    /** 进度槽是否与输入/输出槽重叠。 */
    private boolean overlapsFunctional(int slot) {
        if (slot < 0) return false;
        for (int s : inputSlots) if (s == slot) return true;
        for (int s : outputSlots) if (s == slot) return true;
        return false;
    }

    /** 额外的可交互槽位（不会被背景填充阻挡），子类（如模板机器）可覆盖以加入模板槽等。 */
    protected java.util.Set<Integer> extraFunctionalSlots() {
        return java.util.Collections.emptySet();
    }

    @Override
    protected void constructMenu(BlockMenuPreset preset) {
        // super() 阶段会提前调用一次（字段为 null），此时跳过；由构造器末尾重建 preset 时再真正构建。
        if (inputSlots == null || outputSlots == null) return;
        MachineMenuBuilder.build(preset, this, menu, inputSlots, outputSlots,
                progressSlot(), progressBar, extraFunctionalSlots());
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
                if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            }
            return;
        }
        if (!takeCharge(b.getLocation())) return;
        if (!op.isFinished()) {
            if (pslot >= 0) getMachineProcessor().updateProgressBar(inv, pslot, op);
            op.addProgress(getSpeed());
            return;
        }
        WTRecipe r = active.remove(b.getLocation());
        if (r != null) {
            pushRecipeOutputs(b, inv, r);
        }
        if (pslot >= 0) inv.replaceExistingItem(pslot, progressBar);
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

    /** 用本机器的全部配方匹配（不消耗）。 */
    protected RecipeMatcher.Match findMatch(BlockMenu inv) {
        return RecipeMatcher.findMatch(inv, recipes, inputSlots, outputSlots, posBySlot, sfPrune);
    }

    /** 在给定配方列表中匹配（不消耗；模板机器按当前模板筛选后复用）。 */
    protected RecipeMatcher.Match findMatch(BlockMenu inv, List<WTRecipe> recipeList) {
        return RecipeMatcher.findMatch(inv, recipeList, inputSlots, outputSlots, posBySlot, sfPrune);
    }

    /** 匹配并消耗输入（tick 路径：操作会在机器内暂存，没电也不会丢输入）。 */
    protected MachineRecipe matchRecipes(BlockMenu inv, List<WTRecipe> recipeList) {
        RecipeMatcher.Match m = findMatch(inv, recipeList);
        if (m == null) return null;
        RecipeMatcher.consumeMatch(inv, m, inputSlots);
        return m.recipe;
    }

    /** 指南展示配方缓存。recipes 在构造后不变，展示列表亦不变，首次调用预算并缓存。 */
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

    @Override
    public void rememberFilled(org.bukkit.Location loc, WTRecipe recipe) {
        if (loc != null && recipe != null) lastFilled.put(loc, recipe);
    }

    @Override
    public WTRecipe lastFilledAt(org.bukkit.Location loc) {
        return loc == null ? null : lastFilled.get(loc);
    }

    /** 产物命中：机器 + 该产物命中的工作配方下标（全局顺序）。 */
    public record ProductHit(WTMachine machine, int[] indices) {}

    /**
     * 按主产物身份反查工作配方机器（指南点击产物直接跳大配方展示用）。
     * 主产物身份键与 {@code BigRecipeMenu.productKey} 一致：SF id 优先，否则材质+显示名。
     *
     * @return 命中的机器与配方下标；无任何机器的产物匹配时返回 null
     */
    public static ProductHit findByProduct(ItemStack out) {
        if (out == null || out.getType().isAir()) return null;
        String key = productKey(out);
        for (WTMachine m : INSTANCES) {
            List<WTRecipe> rs = m.getRecipes();
            if (rs == null) continue;
            int[] hits = new int[rs.size()];
            int n = 0;
            for (int i = 0; i < rs.size(); i++) {
                ItemStack[] os = rs.get(i).getOutput();
                ItemStack main = (os.length > 0) ? os[0] : null;
                if (main != null && key.equals(productKey(main))) hits[n++] = i;
            }
            if (n > 0) return new ProductHit(m, Arrays.copyOf(hits, n));
        }
        return null;
    }

    /** 产物身份键：SF id 优先，否则材质+显示名。 */
    private static String productKey(ItemStack out) {
        SlimefunItem sf = SlimefunItem.getByItem(out);
        if (sf != null) return "sf:" + sf.getId();
        String name = out.hasItemMeta() && out.getItemMeta().hasDisplayName()
                ? out.getItemMeta().getDisplayName() : "";
        return "mc:" + out.getType() + ":" + name;
    }
}
