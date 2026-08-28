package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTRecipeMachine;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.ArrayList;
import java.util.List;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 机器配方补全：列出机器全部工作配方（产物代表，分页），玩家选择后自动从背包
 * 将材料填入机器 GUI 的对应输入槽（含大型配方的自定义绑定槽）。
 * <p>交互约定：<ul>
 * <li>左键点击配方：补全该配方一份材料（可多次点击在机器内逐份追加）；</li>
 * <li>右键点击配方：像左键一样逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份（可多次点击累加）；</li>
 * <li>左下角（45）：返回机器页面；右下角（53）：翻页（多页时）。</li>
 * </ul>
 * 机器菜单在打开补全按钮时直接传入（不依赖准星）；无绑定输入按 GUI 布局顺序
 * （槽位升序，12 起）填充，优先堆叠到已放同物品的槽，与 findMatch 映射一致。</p>
 */
public final class RecipeFillMenu {

    private static final int PER_PAGE = 45;
    private static final int SLOT_PAGE = 53;
    private static final int SLOT_BACK = 45;
    /** 右键补全的固定份数：一次补足该配方 N 份所需材料（可在此调整）。 */
    private static final int FILL_GROUP_SIZE = 8;

    private RecipeFillMenu() {}

    /** 打开配方选择菜单。@param menu 玩家当前打开的机器菜单（补全按钮所在），可能为 null 时走准星兜底 */
    public static void open(Player p, WTRecipeMachine machine, BlockMenu menu) {
        open(p, machine, menu, 0);
    }

    public static void open(Player p, WTRecipeMachine machine, BlockMenu menu, int page) {
        List<WTRecipe> recipes = machine.getRecipes();
        if (recipes.isEmpty()) {
            p.sendMessage(ChatColor.RED + "该机器没有可补全的配方");
            return;
        }
        int pages = Math.max(1, (recipes.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        String title = ChatColor.stripColor(machine.getItemName()) + " · 选择配方"
                + (pages > 1 ? " " + (pg + 1) + "/" + pages : "");
        ChestMenu gui = new ChestMenu(title);
        gui.setEmptySlotsClickable(false);
        for (int i = 0; i < 54; i++) {
            gui.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        int start = pg * PER_PAGE;
        int shown = Math.min(PER_PAGE, recipes.size() - start);
        for (int i = 0; i < shown; i++) {
            final int index = start + i;
            final WTRecipe recipe = recipes.get(index);
            gui.addItem(i, describeRecipe(recipe, index), (pl, s, cursor, action) -> {
                if (action.isRightClicked()) {
                    // 右键：补全该配方固定 N 份
                    fillN(pl, machine, recipe, menu);
                } else {
                    // 左键：补全该配方一份（可多次点击追加多份）
                    fill(pl, machine, recipe, menu);
                }
                return false;
            });
        }

        // 左下角（45）：返回机器页面（关闭配方选择，重新打开机器界面）
        gui.addItem(SLOT_BACK, backMachineItem(), (pl, s, cursor, action) -> {
            BlockMenu inv = resolveInv(pl, machine, menu);
            if (inv != null) inv.open(pl);
            return false;
        });

        // 右下角（53）：翻页（多页时）；单页时留空
        if (pages > 1) {
            ItemStack pageBtn = pageItem("配方 " + (pg + 1) + "/" + pages);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + "左键：下一页");
                lore.add(ChatColor.GRAY + "右键：上一页");
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            gui.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                if (action.isRightClicked()) {
                    open(pl, machine, menu, pg - 1);
                } else {
                    open(pl, machine, menu, pg + 1);
                }
                return false;
            });
        }

        gui.open(p);
    }

    /** 左键：补全所选配方一份材料（每次点击在当前槽位基础上追加一份，可多次点击堆叠多份）。 */
    private static void fill(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillOne(p, machine, r, provided);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + "材料已填充一份"
                : ChatColor.YELLOW + "填充完成，但缺少 " + missing + " 种材料");
    }

    /** 右键：像左键一样逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份材料（可多次点击继续累加）。 */
    private static void fillN(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillCore(p, machine, r, provided, FILL_GROUP_SIZE);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + "已补全该配方 " + FILL_GROUP_SIZE + " 份材料"
                : ChatColor.YELLOW + "补全完成，但缺少 " + missing + " 种材料");
    }

    /** 解析机器菜单（传入或准星兜底），校验归属。 */
    private static BlockMenu resolveInv(Player p, WTRecipeMachine machine, BlockMenu provided) {
        BlockMenu inv = provided;
        if (inv == null) {
            Block target = p.getTargetBlockExact(5);
            if (target != null) inv = BlockStorage.getInventory(target.getLocation());
        }
        if (inv == null || inv.getPreset() == null || inv.getPreset().getSlimefunItem() != machine) {
            p.sendMessage(ChatColor.RED + "补全失败：请打开该机器（" + ChatColor.stripColor(machine.getItemName()) + "）后重试");
            return null;
        }
        return inv;
    }

    /**
     * 填充单个配方一份材料（增量语义：每次在当前槽位已有基础上再追加一份），返回缺失材料数。
     */
    private static int fillOne(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided) {
        return fillCore(p, machine, r, provided, 1);
    }

    /** 填充核心：增量语义——在槽内已有基础上追加 portions 份所需材料。返回缺失材料数（不发送消息）。 */
    private static int fillCore(Player p, WTRecipeMachine machine, WTRecipe r, BlockMenu provided, int portions) {
        BlockMenu inv = resolveInv(p, machine, provided);
        if (inv == null) return 0;
        int[] inputSlots = machine.getInputSlots();
        // 按 GUI 布局顺序（槽位数值升序，从上到下从左到右）遍历——声明顺序可能不同（如恒温陈酿皿 [32,12,...]）
        int[] layoutSlots = inputSlots.clone();
        java.util.Arrays.sort(layoutSlots);
        boolean[] usedInput = new boolean[layoutSlots.length];
        int missing = 0;
        ItemStack[] input = r.getInput();
        for (int i = 0; i < input.length; i++) {
            ItemStack need = input[i];
            if (need == null) continue;
            int slot = r.inSlot(i);
            if (slot < 0) {
                // 无绑定输入：优先堆叠到已放同物品且未满的槽，其次空槽
                slot = findStackOrEmpty(inv, need, layoutSlots, usedInput);
            }
            if (slot < 0) {
                p.sendMessage(ChatColor.RED + "补全失败：没有空余输入槽");
                return missing;
            }
            ItemStack cur = inv.getItemInSlot(slot);
            if (cur != null && !cur.getType().isAir() && !SlimefunUtils.isItemSimilar(cur, need, true)) {
                p.sendMessage(ChatColor.YELLOW + "槽位 " + (slot + 1) + " 被其他物品占用，跳过该材料");
                continue;
            }
            int have = (cur == null || cur.getType().isAir()) ? 0 : cur.getAmount();
            int capacity = (cur == null || cur.getType().isAir()) ? need.getMaxStackSize() : cur.getMaxStackSize();
            // 本次目标量：在已有基础上追加 portions 份（左键 1 份、右键 FILL_GROUP_SIZE 份）
            int target = have + need.getAmount() * portions;
            if (have >= target) continue;
            int take = Math.min(target - have, capacity - have);
            if (take <= 0) continue;
            // 有多少拿多少：背包不足时把现有部分也放入机器并提示差额，绝不吞掉玩家材料
            ItemStack collected = takeFromPlayer(p, need, take);
            if (collected == null || collected.getAmount() <= 0) {
                missing++;
                p.sendMessage(ChatColor.RED + "背包缺少 " + displayName(need) + " ×" + take);
                continue;
            }
            int got = collected.getAmount();
            if (have == 0) {
                inv.replaceExistingItem(slot, collected);
            } else {
                ItemStack merged = cur.clone();
                merged.setAmount(have + got);
                inv.replaceExistingItem(slot, merged);
            }
            if (got < take) {
                missing++;
                p.sendMessage(ChatColor.RED + "材料不足：" + displayName(need) + " 还差 " + (take - got)
                        + " 个（已放入 " + got + " 个）");
            }
        }
        return missing;
    }

    /** 无绑定输入槽选择：优先「已放同物品且未满」的槽（堆叠追加），其次空槽。 */
    private static int findStackOrEmpty(BlockMenu inv, ItemStack need, int[] layoutSlots, boolean[] usedInput) {
        // 1) 堆叠到已有同物品且未满的槽
        for (int k = 0; k < layoutSlots.length; k++) {
            if (usedInput[k]) continue;
            ItemStack cur = inv.getItemInSlot(layoutSlots[k]);
            if (cur != null && !cur.getType().isAir()
                    && SlimefunUtils.isItemSimilar(cur, need, true)
                    && cur.getAmount() < cur.getMaxStackSize()) {
                usedInput[k] = true;
                return layoutSlots[k];
            }
        }
        // 2) 空槽
        for (int k = 0; k < layoutSlots.length; k++) {
            if (usedInput[k]) continue;
            ItemStack cur = inv.getItemInSlot(layoutSlots[k]);
            if (cur == null || cur.getType().isAir()) {
                usedInput[k] = true;
                return layoutSlots[k];
            }
        }
        return -1;
    }

    /** 从玩家背包收集至多 amount 个匹配 need 的物品（有多少拿多少、全部扣除），返回实收堆；背包一个都没有时返回 null。 */
    private static ItemStack takeFromPlayer(Player p, ItemStack need, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack result = null;
        int left = amount;
        for (int k = 0; k < inv.getSize() && left > 0; k++) {
            ItemStack it = inv.getItem(k);
            if (it == null || it.getType().isAir()) continue;
            if (!SlimefunUtils.isItemSimilar(it, need, true)) continue;
            int take = Math.min(left, it.getAmount());
            if (take <= 0) continue;
            if (result == null) {
                result = it.clone();
                result.setAmount(take);
            } else {
                result.setAmount(result.getAmount() + take);
            }
            int rest = it.getAmount() - take;
            if (rest <= 0) inv.setItem(k, null);
            else {
                ItemStack reduced = it.clone();
                reduced.setAmount(rest);
                inv.setItem(k, reduced);
            }
            left -= take;
            if (left <= 0) break;
        }
        return result;
    }

    private static ItemStack describeRecipe(WTRecipe r, int index) {
        ItemStack[] out = r.getOutput();
        ItemStack icon = (out.length > 0 && out[0] != null) ? out[0].clone() : new ItemStack(Material.BARRIER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "配方 " + (index + 1));
            lore.add(ChatColor.GRAY + "材料 " + countInputs(r) + " 项 · 耗时 " + (r.getTicks() / 2) + "s");
            lore.add(ChatColor.DARK_GRAY + "左键：补全该配方一份（可多次点击追加）");
            lore.add(ChatColor.DARK_GRAY + "右键：补全该配方 " + FILL_GROUP_SIZE + " 份（可多次点击追加）");
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static int countInputs(WTRecipe r) {
        int n = 0;
        for (ItemStack in : r.getInput()) {
            if (in != null) n++;
        }
        return n;
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(stack.getItemMeta().getDisplayName());
        }
        return stack.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static ItemStack pageItem(String name) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + name);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 左下角「返回机器」按钮：关闭配方选择，重新打开机器界面。 */
    private static ItemStack backMachineItem() {
        ItemStack it = new ItemStack(Material.OAK_DOOR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "返回机器");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "关闭配方选择，回到机器界面");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
