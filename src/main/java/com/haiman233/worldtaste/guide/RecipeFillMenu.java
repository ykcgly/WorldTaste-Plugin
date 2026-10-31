package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.machines.WTRecipe;
import com.haiman233.worldtaste.machines.WTMachine;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import com.haiman233.worldtaste.util.Lang;

/**
 * 机器配方补全：列出机器全部工作配方（产物代表，分页），玩家选择后自动从背包
 * 将材料填入机器 GUI 的对应输入槽（含大型配方的自定义绑定槽）。
 * <p>交互约定：<ul>
 * <li>机器界面补全按钮（知识之书）：左键打开本界面；右键补全上次的配方 1 份；
 *     Shift+右键补全上次的配方 8 份（「上次的配方」按方块位置记忆，见
 *     {@link WTRecipeMachine#rememberFilled}）；</li>
 * <li>左键点击配方：补全该配方一份材料（可多次点击在机器内逐份追加）；</li>
 * <li>右键点击配方：像左键一样逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份（可多次点击累加）；</li>
 * <li>49 格搜索（指南针）：左键进入聊天输入搜索（拦截聊天不上公屏，支持关键词与拼音全拼/首字母），
 *     搜索中右键取消搜索；</li>
 * <li>左下角（45）：返回机器页面；右下角（53）：翻页（多页时）。</li>
 * </ul>
 * 机器菜单在打开补全按钮时直接传入（不依赖准星）；无绑定输入按 GUI 布局顺序
 * （槽位升序，12 起）填充，优先堆叠到已放同物品的槽，与 findMatch 映射一致。</p>
 */
public final class RecipeFillMenu {

    private static final int PER_PAGE = 45;
    private static final int SLOT_SEARCH = 49;
    private static final int SLOT_PAGE = 53;
    private static final int SLOT_BACK = 45;
    /** 右键补全的固定份数：一次补足该配方 N 份所需材料（可在此调整）。 */
    private static final int FILL_GROUP_SIZE = 8;

    /** 待输入搜索词的玩家（聊天拦截用）：uuid → 搜索上下文（机器 + 菜单来源）。 */
    private static final Map<UUID, SearchCtx> pendingSearch = new ConcurrentHashMap<>();
    private static volatile boolean chatRegistered = false;
    /** 拼音库（JEG 内嵌 houbb pinyin）是否可用；首次加载失败后不再尝试。 */
    private static volatile boolean pinyinAvailable = true;

    /** 一次待输入搜索的上下文。 */
    private record SearchCtx(WTMachine machine, BlockMenu menu) {}

    private RecipeFillMenu() {}

    /** 注册搜索聊天拦截（幂等；插件启用时调用一次）。 */
    public static void register() {
        if (chatRegistered) return;
        chatRegistered = true;
        Bukkit.getPluginManager().registerEvents(new ChatSearchListener(), WT.plugin);
    }

    /** 打开配方选择菜单。@param menu 玩家当前打开的机器菜单（补全按钮所在），可能为 null 时走准星兜底 */
    public static void open(Player p, WTMachine machine, BlockMenu menu) {
        open(p, machine, menu, 0, null);
    }

    public static void open(Player p, WTMachine machine, BlockMenu menu, int page) {
        open(p, machine, menu, page, null);
    }

    /**
     * 打开配方选择菜单。
     *
     * @param search 搜索词（null/空白 = 全部配方；否则按产物/材料名称与拼音过滤）
     */
    public static void open(Player p, WTMachine machine, BlockMenu menu, int page, String search) {
        List<WTRecipe> all = machine.getRecipes();
        List<WTRecipe> recipes = all;
        boolean searching = search != null && !search.isBlank();
        if (searching) {
            String q = search.trim();
            recipes = new ArrayList<>();
            for (WTRecipe r : all) {
                if (matchesQuery(r, q)) recipes.add(r);
            }
            if (recipes.isEmpty()) {
                p.sendMessage(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.001") + search + Lang.get("ui.recipefillmenu.002"));
                return;
            }
        } else if (recipes.isEmpty()) {
            p.sendMessage(ChatColor.RED + Lang.get("ui.recipefillmenu.003"));
            return;
        }
        int pages = Math.max(1, (recipes.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        String title = ChatColor.stripColor(machine.getItemName()) + Lang.get("ui.recipefillmenu.004")
                + (searching ? Lang.get("ui.recipefillmenu.005") + search : "")
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

        // 49 格搜索（指南针）：左键进入聊天输入；搜索中右键取消搜索
        gui.addItem(SLOT_SEARCH, searchButton(), (pl, s, cursor, action) -> {
            if (action.isRightClicked()) {
                if (searching) {
                    pl.sendMessage(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.006"));
                    open(pl, machine, menu, 0, null);
                }
                return false;
            }
            promptSearch(pl, machine, menu);
            return false;
        });

        // 右下角（53）：翻页（多页时）；单页时留空
        if (pages > 1) {
            ItemStack pageBtn = pageItem(Lang.get("ui.recipefillmenu.007") + (pg + 1) + "/" + pages);
            ItemMeta pm = pageBtn.getItemMeta();
            if (pm != null) {
                List<String> lore = pm.getLore() != null ? new ArrayList<>(pm.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(ChatColor.GRAY + Lang.get("ui.recipefillmenu.008"));
                lore.add(ChatColor.GRAY + Lang.get("ui.recipefillmenu.009"));
                pm.setLore(lore);
                pageBtn.setItemMeta(pm);
            }
            gui.addItem(SLOT_PAGE, pageBtn, (pl, s, cursor, action) -> {
                open(pl, machine, menu, action.isRightClicked() ? pg - 1 : pg + 1, search);
                return false;
            });
        }

        gui.open(p);
    }

    /**
     * 一键补全「上一次补全的配方」（机器界面补全按钮右键/Shift+右键入口）。
     *
     * @param portions 份数（1 或 8）
     */
    public static void refill(Player p, WTMachine machine, BlockMenu provided, int portions) {
        BlockMenu inv = resolveInv(p, machine, provided);
        if (inv == null) return;
        WTRecipe last = machine.lastFilledAt(inv.getLocation());
        if (last == null) {
            p.sendMessage(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.010"));
            return;
        }
        int missing = fillCore(p, machine, last, provided, portions);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + Lang.get("ui.recipefillmenu.011") + portions
                : ChatColor.YELLOW + Lang.get("ui.recipefillmenu.012") + missing + Lang.get("ui.recipefillmenu.013"));
    }

    /** 搜索按钮点击：关闭界面，转入聊天输入（拦截该条聊天不上公屏）。 */
    private static void promptSearch(Player p, WTMachine machine, BlockMenu menu) {
        pendingSearch.put(p.getUniqueId(), new SearchCtx(machine, menu));
        p.closeInventory();
        p.sendMessage(ChatColor.GREEN + Lang.get("ui.recipefillmenu.014"));
    }

    /** 搜索聊天拦截：捕获输入词并取消公屏广播（LOWEST 尽早取消）。 */
    public static final class ChatSearchListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(AsyncPlayerChatEvent e) {
            SearchCtx ctx = pendingSearch.remove(e.getPlayer().getUniqueId());
            if (ctx == null) return;
            e.setCancelled(true);
            String msg = e.getMessage() == null ? "" : e.getMessage().trim();
            Bukkit.getScheduler().runTask(WT.plugin, () -> {
                if (msg.equalsIgnoreCase("取消")) {
                    e.getPlayer().sendMessage(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.006"));
                    open(e.getPlayer(), ctx.machine(), ctx.menu(), 0, null);
                    return;
                }
                open(e.getPlayer(), ctx.machine(), ctx.menu(), 0, msg);
            });
        }
    }

    /** 配方是否匹配搜索词：产物或任一材料的名称（原文/拼音全拼/拼音首字母）包含关键词。 */
    private static boolean matchesQuery(WTRecipe r, String query) {
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        if (q.isEmpty()) return true;
        ItemStack[] out = r.getOutput();
        if (out.length > 0 && out[0] != null && nameMatches(out[0], q)) return true;
        for (ItemStack in : r.getInput()) {
            if (in != null && nameMatches(in, q)) return true;
        }
        return false;
    }

    private static boolean nameMatches(ItemStack it, String q) {
        String plain = plainName(it).toLowerCase(Locale.ROOT);
        if (plain.contains(q)) return true;
        if (!pinyinAvailable) return false;
        String full = pinyin(plain, false);
        if (!full.isEmpty() && full.replace(" ", "").contains(q)) return true;
        String fl = pinyin(plain, true);
        return !fl.isEmpty() && fl.replace(" ", "").contains(q);
    }

    /** 名称（去色显示名；无显示名回退材质中文名/英文名）。 */
    private static String plainName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(it.getItemMeta().getDisplayName());
        }
        return com.haiman233.worldtaste.util.ZhNames.orEnglish(it.getType());
    }

    /**
     * 拼音转换（全拼 / 首字母）。拼音库由 JEG 内嵌（com.github.houbb.pinyin）——
     * 经 {@link PinyinBridge} 懒加载，JEG 未安装时 {@code pinyinAvailable} 置 false，
     * 搜索降级为纯关键词匹配（无反射、无额外依赖）。
     */
    private static String pinyin(String text, boolean firstLetter) {
        try {
            return PinyinBridge.toPinyin(text, firstLetter);
        } catch (Throwable t) {
            pinyinAvailable = false;
            return "";
        }
    }

    /** 懒加载桥：仅在本类首次执行到拼音转换时才初始化，未装 JEG 不会触发类解析。 */
    private static final class PinyinBridge {
        static String toPinyin(String text, boolean firstLetter) {
            if (firstLetter) {
                return com.github.houbb.pinyin.util.PinyinHelper.toPinyin(text,
                        com.github.houbb.pinyin.constant.enums.PinyinStyleEnum.FIRST_LETTER);
            }
            return com.github.houbb.pinyin.util.PinyinHelper.toPinyin(text,
                    com.github.houbb.pinyin.constant.enums.PinyinStyleEnum.NORMAL);
        }
    }

    /** 左键：补全所选配方一份材料（每次点击在当前槽位基础上追加一份，可多次点击堆叠多份）。 */
    private static void fill(Player p, WTMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillOne(p, machine, r, provided);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + Lang.get("ui.recipefillmenu.015")
                : ChatColor.YELLOW + Lang.get("ui.recipefillmenu.016") + missing + Lang.get("ui.recipefillmenu.013"));
    }

    /** 右键：像左键一样逐份追加，但一次追加 {@link #FILL_GROUP_SIZE} 份材料（可多次点击继续累加）。 */
    private static void fillN(Player p, WTMachine machine, WTRecipe r, BlockMenu provided) {
        int missing = fillCore(p, machine, r, provided, FILL_GROUP_SIZE);
        p.sendMessage(missing == 0
                ? ChatColor.GREEN + Lang.get("ui.recipefillmenu.017") + FILL_GROUP_SIZE + Lang.get("ui.recipefillmenu.018")
                : ChatColor.YELLOW + Lang.get("ui.recipefillmenu.012") + missing + Lang.get("ui.recipefillmenu.013"));
    }

    /** 解析机器菜单（传入或准星兜底），校验归属。 */
    private static BlockMenu resolveInv(Player p, WTMachine machine, BlockMenu provided) {
        BlockMenu inv = provided;
        if (inv == null) {
            Block target = p.getTargetBlockExact(5);
            if (target != null) inv = BlockStorage.getInventory(target.getLocation());
        }
        if (inv == null || inv.getPreset() == null || inv.getPreset().getSlimefunItem() != machine) {
            p.sendMessage(ChatColor.RED + Lang.get("ui.recipefillmenu.019") + ChatColor.stripColor(machine.getItemName()) + Lang.get("ui.recipefillmenu.020"));
            return null;
        }
        return inv;
    }

    /**
     * 填充单个配方一份材料（增量语义：每次在当前槽位已有基础上再追加一份），返回缺失材料数。
     */
    private static int fillOne(Player p, WTMachine machine, WTRecipe r, BlockMenu provided) {
        return fillCore(p, machine, r, provided, 1);
    }

    /** 填充核心：增量语义——在槽内已有基础上追加 portions 份所需材料。返回缺失材料数（不发送消息）。 */
    private static int fillCore(Player p, WTMachine machine, WTRecipe r, BlockMenu provided, int portions) {
        BlockMenu inv = resolveInv(p, machine, provided);
        if (inv == null) return 0;
        // 记录「上一次补全的配方」（按方块位置）：供机器界面补全按钮右键/Shift+右键一键复补
        machine.rememberFilled(inv.getLocation(), r);
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
                p.sendMessage(ChatColor.RED + Lang.get("ui.recipefillmenu.021"));
                return missing;
            }
            ItemStack cur = inv.getItemInSlot(slot);
            if (cur != null && !cur.getType().isAir()
                    && !com.haiman233.worldtaste.util.RecipeMatch.input(cur, need, false)) {
                p.sendMessage(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.022") + (slot + 1) + Lang.get("ui.recipefillmenu.023"));
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
                p.sendMessage(ChatColor.RED + Lang.get("ui.recipefillmenu.024") + displayName(need) + " ×" + take);
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
                p.sendMessage(ChatColor.RED + Lang.get("ui.recipefillmenu.025") + displayName(need) + Lang.get("ui.recipefillmenu.026") + (take - got)
                        + Lang.get("ui.recipefillmenu.027") + got + Lang.get("ui.recipefillmenu.028"));
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
                    && com.haiman233.worldtaste.util.RecipeMatch.input(cur, need, false)
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
            // 实体桶（河豚桶等）带 bucket_entity_data 组件：宽松匹配才能从背包收集到玩家实际捕获的桶
            if (!com.haiman233.worldtaste.util.RecipeMatch.input(it, need, false)) continue;
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
            lore.add(ChatColor.GREEN + Lang.get("ui.recipefillmenu.007") + (index + 1));
            lore.add(ChatColor.GRAY + Lang.get("ui.recipefillmenu.029") + countInputs(r) + Lang.get("ui.recipefillmenu.030") + (r.getTicks() / 2) + "s");
            lore.add(ChatColor.DARK_GRAY + Lang.get("ui.recipefillmenu.031"));
            lore.add(ChatColor.DARK_GRAY + Lang.get("ui.recipefillmenu.032") + FILL_GROUP_SIZE + Lang.get("ui.recipefillmenu.033"));
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
        return com.haiman233.worldtaste.util.ZhNames.orEnglish(stack.getType());
    }

    /** 49 格搜索按钮（指南针）。 */
    private static ItemStack searchButton() {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + Lang.get("ui.recipefillmenu.034"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GREEN + Lang.get("ui.recipefillmenu.035"));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
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
            meta.setDisplayName(ChatColor.YELLOW + Lang.get("ui.recipefillmenu.036"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + Lang.get("ui.recipefillmenu.037"));
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }
}
