package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 酒窖管理器机器页面（粘液原生 {@link BlockMenuPreset} 体系，与本体机器同款打开方式）。
 *
 * <p>静态布局（背景/装饰/按钮与点击处理器）由 {@link #setupMenu} 在 preset init 中绘制一次，
 * 每 BlockMenu 克隆共享；动态槽位（时钟/清空/羊毛/液位线/酒曲展示/灌装按钮/命名牌）由
 * 10 tick 周期任务对<b>有查看者</b>的界面 {@code replaceExistingItem} 实时刷新。
 * 界面内容随方块数据持久化（Slimefun 原生机制）：玩家放入的瓶子/酒曲/成品关页不丢，
 * 重开原样还在，多玩家同时查看共享同一份界面内容。</p>
 *
 * <p>槽位布局：槽 0 结构展示入口、槽 1 配方入口、槽 4 时钟（模式/计时，点击切换，
 * shift+右键切换自动陈化）、槽 7 黄板（污染清空；非运行态 shift+右键直接清空机内液体与
 * 已投入酒曲）、右列粉色液位线（24 单位，1 格 4 单位）、添料区 3×3 空槽 19-21/28-30/37-39
 * （投果汁：瓶 +1/桶 +3，返还空容器；清水可投入稀释）、槽 13 酒曲放置位（投入后只消耗
 * 1 个，槽 22 显示对应酒曲图标）、槽 31 羊毛启动/关闭（酿造关闭=报废、陈化关闭=出酒）、
 * 出酒区 3×3 空槽 23-25/32-34/41-43（只出不进）、槽 40 灌装槽（放原版玻璃瓶）、
 * 槽 49 炼药锅「点我装瓶」、槽 45 命名牌。计时推进由管理器 ticker 调用 {@link #advance}。</p>
 */
public final class CellarMenu {

    private static final int SLOT_CLOCK = 4;
    private static final int SLOT_CLEAR = 7;
    private static final int SLOT_WOOL = 31;
    private static final int[] GAUGE = {8, 17, 26, 35, 44, 53};
    /** 酒曲投放位（玩家槽）：只接受酒曲，投入后只消耗 1 个。 */
    private static final int SLOT_YEAST_INPUT = 13;
    /** 酒曲展示位（只读）：与 4 时钟 / 31 羊毛 / 40 产物预览同列。 */
    private static final int SLOT_YEAST = 22;
    private static final int[] INPUT_SLOTS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private static final int SLOT_FILL = 40; // 灌装槽：留空，放入玻璃瓶即灌装
    private static final int[] OUTPUT_SLOTS = {23, 24, 25, 32, 33, 34, 41, 42, 43};
    /** 灌装槽按钮（炼药锅「点我装瓶」）。 */
    private static final int SLOT_FILL_DISPLAY = 49;
    /** 左下角命名牌按钮：为酒窖命名（仅放置者，配置可关）。 */
    private static final int SLOT_NAMETAG = 45;
    private static final int[] GREEN_DECO = {10, 11, 12, 18, 27, 36, 46, 47, 48};
    private static final int[] RED_DECO = {14, 15, 16, 50, 51, 52};
    private static final long TICK_MS = 50;

    /** 正在命名的玩家 → 待命名的酒窖方块位置（铁砧打开时酒窖页面会话已结束）。 */
    private static final Map<UUID, Location> PENDING_CELLAR = new HashMap<>();
    /** 正在命名的玩家 → 其命名铁砧界面（打开后填入占位物品）。 */
    private static final Map<UUID, org.bukkit.inventory.Inventory> NAMING = new HashMap<>();
    /** 已创建界面的酒窖（位置 → BlockMenu）：刷新任务只遍历这些界面。 */
    private static final Map<Location, BlockMenu> MENUS = new HashMap<>();
    private static BukkitTask refreshTask;

    private CellarMenu() {}

    public static void register() {
        refreshTask = Bukkit.getScheduler().runTaskTimer(WT.plugin, CellarMenu::refreshAll, 10, 10);
        Bukkit.getPluginManager().registerEvents(new CloseListener(), WT.plugin);
    }

    /**
     * preset 静态布局：背景只覆盖「非玩家槽位」（玩家槽留空，物品由 BlockMenu 原生持久化），
     * 全部交互槽注册点击处理器（处理器内经事件 holder 取 {@link BlockMenu} 定位方块）。
     */
    static void setupMenu(ChestMenu menu) {
        menu.setEmptySlotsClickable(true);
        menu.setPlayerInventoryClickable(true);
        for (int i = 0; i < 54; i++) {
            if (isPlayerSlot(i)) continue;
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 多方块结构展示入口（左上角）
        menu.addItem(0, pane(Material.SPYGLASS, "§b多方块结构",
                List.of(leg("§7查看酒窖的 3×3×3 结构分层"), leg(""), leg(ChatColor.YELLOW + "点击打开"))),
                handler((pl, bm, cur) -> {
                    CellarStructureMenu.open(pl, bm.getBlock());
                    return false;
                }));
        // 配方展示入口（结构按钮旁）
        menu.addItem(1, pane(Material.BOOK, "§e酒窖配方",
                List.of(leg("§7查看酒窖可酿造的配方一览"), leg(""), leg(ChatColor.YELLOW + "点击打开"))),
                handler((pl, bm, cur) -> {
                    CellarRecipeMenu.openRecipes(pl, 0, bm.getBlock());
                    return false;
                }));
        // 时钟：Shift+右键切换自动陈化；普通点击切换模式
        menu.addMenuClickHandler(SLOT_CLOCK, new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                if (!(e.getView().getTopInventory().getHolder() instanceof BlockMenu bm)) return false;
                Block manager = bm.getBlock();
                if (e.isShiftClick() && e.isRightClick()) toggleAutoAge(pl, manager);
                else toggleMode(pl, manager);
                paint(bm, manager);
                return false;
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                return false;
            }
        });
        // 清空按钮：普通点击仅在污染态生效。Shift+右键走 Bukkit 监听（CSCoreLib 不一定带上右键标记）。
        menu.addMenuClickHandler(SLOT_CLEAR, handler((pl, bm, cur) -> {
            clearContaminated(pl, bm.getBlock(), false);
            paint(bm, bm.getBlock());
            return false;
        }));
        menu.addMenuClickHandler(SLOT_WOOL, handler((pl, bm, cur) -> {
            Block manager = bm.getBlock();
            woolClick(pl, manager);
            paint(bm, manager);
            return false;
        }));
        // 灌装槽按钮（炼药锅图标）：点击灌装一次
        menu.addMenuClickHandler(SLOT_FILL_DISPLAY, handler((pl, bm, cur) -> {
            Block manager = bm.getBlock();
            fillOne(pl, manager, bm);
            paint(bm, manager);
            return false;
        }));
        // 左下角命名牌：为酒窖命名（配置可关；仅放置者）
        if (com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            menu.addMenuClickHandler(SLOT_NAMETAG, handler((pl, bm, cur) -> {
                clickNameTag(pl, bm.getBlock());
                paint(bm, bm.getBlock());
                return false;
            }));
        }

        // 装饰文本（绿/红玻璃板标识）
        for (int slot : GREEN_DECO) {
            menu.addItem(slot, pane(Material.LIME_STAINED_GLASS_PANE, "§a添料区",
                    List.of(leg("§7添料槽：投入瓶装/桶装果汁"), leg("§7瓶 +1 单位，桶 +3 单位"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }
        for (int slot : RED_DECO) {
            menu.addItem(slot, pane(Material.RED_STAINED_GLASS_PANE, "§c输出槽",
                    List.of(leg("§7在这里输出产物"), leg("§7物品会保存在机器内"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }

        // 添料区：果汁投料 + 出酒阶段玻璃瓶灌装；禁止果酒回流，酒曲只能在 13 号槽投放
        for (int slot : INPUT_SLOTS) {
            menu.addMenuClickHandler(slot, handler((pl, bm, cursor) -> {
                Block manager = bm.getBlock();
                WineCellarState st = WineCellarState.get(manager);
                // 只拦「放入」：光标上有物品才算放入；空手提起、shift 取出一律放行。
                ItemStack incoming = (cursor != null && !cursor.getType().isAir()) ? cursor : null;
                // 运行中/已污染：放入的物品不会被吸收，会一直滞留槽内，直接拦截
                if (incoming != null && st.phase() != WineCellarState.Phase.IDLE
                        && st.phase() != WineCellarState.Phase.READY) {
                    pl.sendMessage("§c机器未处于待机/出酒状态，不能放入物品！");
                    return false;
                }
                if (isWine(incoming)) {
                    pl.sendMessage("§c陈酿果酒不能再次放入酒窖！");
                    return false;
                }
                if (incoming != null && isJuice(incoming) && st.hasAlcohol()) {
                    pl.sendMessage("§c酒窖中已含酒精液体，不能投入果汁！");
                    return false;
                }
                if (isYeast(incoming)) {
                    pl.sendMessage("§c酒曲请放入酒曲槽位！");
                    return false;
                }
                // 液位余量校验：放不下的果汁/清水不收（滞留槽内不被吸收）
                if (incoming != null) {
                    String reject = capacityReject(st, incoming);
                    if (reject != null) {
                        pl.sendMessage(reject);
                        return false;
                    }
                }
                return true;
            }));
        }
        // 出酒区：只出不进（成品由灌装槽输出到此处，不接受玩家放入）
        for (int slot : OUTPUT_SLOTS) {
            menu.addMenuClickHandler(slot, handler((pl, bm, cursor) -> {
                if (cursor == null || cursor.getType().isAir()) return true;
                pl.sendMessage("§c你不能在这里放置物品！");
                return false;
            }));
        }
        // 酒曲投放位：只接受酒曲，且每台机器限一种（投入后只消耗 1 个）
        menu.addMenuClickHandler(SLOT_YEAST_INPUT, handler((pl, bm, cursor) -> {
            if (cursor == null || cursor.getType().isAir()) return true;
            if (!isYeast(cursor)) {
                pl.sendMessage("§c酒曲槽只能放入酒曲！");
                return false;
            }
            Block manager = bm.getBlock();
            WineCellarState st = WineCellarState.get(manager);
            // 运行中/出酒阶段加入酒曲不会被使用，只会滞留槽内
            if (st.phase() != WineCellarState.Phase.IDLE) {
                pl.sendMessage("§c只有待机状态才能加入酒曲！");
                return false;
            }
            if (st.yeast() != null) {
                pl.sendMessage("§c该酒窖已加入过酒曲！");
                return false;
            }
            return true;
        }));
    }

    /** 新界面对象：登记进刷新表并立即绘制一次动态槽位。 */
    static void onNewInstance(BlockMenu menu, Block b) {
        MENUS.put(b.getLocation(), menu);
        paint(menu, b);
    }

    /** 重新打开某台酒窖的机器页面（子页面返回用）。 */
    public static void open(Player p, Block manager) {
        BlockMenu menu = me.mrCookieSlime.Slimefun.api.BlockStorage.getInventory(manager);
        if (menu != null) menu.open(p);
    }

    /** 简单处理器适配：由 Bukkit 点击事件解析出对应 BlockMenu（玩家槽校验由调用方完成）。 */
    private interface MenuClick {
        boolean run(Player pl, BlockMenu menu, ItemStack cursor);
    }

    private static ChestMenu.AdvancedMenuClickHandler handler(MenuClick click) {
        return new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                return e.getView().getTopInventory().getHolder() instanceof BlockMenu menu
                        && click.run(pl, menu, cursor);
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                return true;
            }
        };
    }

    /** 玩家可自由存取的槽位（布局时不放背景，物品随方块持久化）。 */
    private static boolean isPlayerSlot(int slot) {
        for (int s : INPUT_SLOTS) {
            if (s == slot) return true;
        }
        for (int s : OUTPUT_SLOTS) {
            if (s == slot) return true;
        }
        return slot == SLOT_YEAST_INPUT || slot == SLOT_FILL;
    }

    /** 是否为陈酿果酒（不允许再次投入酒窖）。 */
    private static boolean isWine(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer()
                        .has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE);
    }

    /** 是否为酒曲（在 {@link JuicerRecipe#yeastBonus} 加成表内的粘液物品）。 */
    private static boolean isYeast(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(it);
        return sf != null && JuicerRecipe.yeastBonus(sf.getId()) >= 0;
    }

    /** 是否为果汁容器（带组成数据的瓶/桶装产物，非果酒）。 */
    private static boolean isJuice(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || isWine(it)) return false;
        String c = it.getItemMeta().getPersistentDataContainer()
                .get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
        return c != null && !c.isEmpty();
    }

    /** 原版水瓶判定（药水材质且基础药水类型为水）。 */
    private static boolean isWaterBottle(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !it.hasItemMeta()) return false;
        return it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm
                && pm.getBasePotionType() == org.bukkit.potion.PotionType.WATER;
    }

    /** 启动前电力校验：管理器与温控器双机蓄电均需 ≥ 各自每 tick 消耗。 */
    private static boolean hasEnoughPower(Block manager) {
        if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager wm)) {
            return false;
        }
        if (wm.getCharge(manager.getLocation()) < WineCellarManager.CONSUMPTION) return false;
        Block partner = CellarStructure.partner(manager, false);
        if (partner == null
                || !(me.mrCookieSlime.Slimefun.api.BlockStorage.check(partner)
                        instanceof TemperatureController ctrl)) {
            return false;
        }
        return ctrl.getCharge(partner.getLocation()) >= TemperatureController.CONSUMPTION;
    }

    /**
     * 校验待放入的果汁/清水是否还有液位余量，放不下时返回拒绝提示，可放返回 null。
     * （放不下的果汁/清水只会滞留槽内不被吸收，放入前就拦下）
     */
    private static String capacityReject(WineCellarState st, ItemStack incoming) {
        if (isJuice(incoming)) {
            boolean bucket = incoming.getItemMeta().getPersistentDataContainer()
                    .has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
            if (!st.canAccept(bucket ? 3 : 1)) {
                return "§c液位余量不足，无法投入" + (bucket ? "桶装" : "瓶装") + "果汁！";
            }
        } else if (isVanilla(incoming, Material.WATER_BUCKET)) {
            if (!st.canAccept(3)) return "§c液位余量不足，无法投入清水！";
        } else if (incoming.getType() == Material.POTION
                && isVanilla(incoming, Material.POTION) && isWaterBottle(incoming)) {
            if (!st.canAccept(1)) return "§c液位余量不足，无法投入清水！";
        }
        return null;
    }

    /** 动态槽位刷新（时钟/清空/羊毛/液位线/酒曲展示/灌装按钮/命名牌）。 */
    private static void paint(BlockMenu menu, Block manager) {
        scanSlots(menu, manager);
        WineCellarState st = WineCellarState.get(manager);
        menu.replaceExistingItem(SLOT_CLOCK, clockItem(st));
        menu.replaceExistingItem(SLOT_CLEAR, clearItem(st));
        menu.replaceExistingItem(SLOT_WOOL, woolItem(st));
        int filled = st.phase() == WineCellarState.Phase.CONTAMINATED
                ? GAUGE.length : (int) Math.ceil(st.units() / 4.0);
        int idx = 0;
        for (int i = GAUGE.length - 1; i >= 0; i--) {
            int slot = GAUGE[i];
            boolean full = idx < filled;
            idx++;
            if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
                menu.replaceExistingItem(slot, pane(Material.BLACK_STAINED_GLASS_PANE, "§8受污染的液体",
                        List.of(leg("§7点击黄色玻璃板清空复原"))));
                continue;
            }
            if (full) {
                List<Component> lore = new ArrayList<>();
                lore.add(leg("§7液位: §e" + st.units() + "§7/" + WineCellarState.CAPACITY + " 单位"));
                if (st.hasAlcohol()) lore.add(leg("§c酒精度: §e" + fmt(st.alcohol()) + "°"));
                // 同种果汁合并显示「倒入的材料份数」：每单位组成 × 批次单位数
                // （如 2 单位苹果汁 → 苹果×2；甜浆果每瓶 3 个，2 单位 → 甜浆果×6）
                Map<String, Double> merged = new LinkedHashMap<>();
                for (WineCellarState.Liquid lq : st.liquids()) {
                    for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                        merged.merge(e.getKey(), e.getValue() * lq.units(), Double::sum);
                    }
                }
                for (Map.Entry<String, Double> e : merged.entrySet()) {
                    // 用可翻译组件拼接，客户端按语言文件渲染出中文物品名
                    lore.add(LegacyComponentSerializer.legacySection().deserialize("§b")
                            .append(JuicerRecipe.nameComponent(e.getKey()))
                            .append(Component.text(" ×" + JuicerRecipe.formatFraction(e.getValue()),
                                    net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                }
                if (st.waterUnits() > 0) {
                    lore.add(leg("§9清水 ×" + st.waterUnits()));
                }
                menu.replaceExistingItem(slot, pane(Material.PINK_STAINED_GLASS_PANE, "§d液位线", lore));
            } else {
                int free = Math.max(0, WineCellarState.CAPACITY - (full ? filled * 4 : st.units()));
                menu.replaceExistingItem(slot, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7液位线（空）",
                        List.of(leg("§7空余容量: §e" + free + "§7 单位"))));
            }
        }
        menu.replaceExistingItem(SLOT_YEAST, yeastPane(st));
        menu.replaceExistingItem(SLOT_FILL_DISPLAY, fillPane());
        // 命名牌按钮：实时刷新当前名称展示（未开启功能时保持背景板）
        if (com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            menu.replaceExistingItem(SLOT_NAMETAG, nameTagItem(st));
        }
    }

    /** 灌装槽按钮（炼药锅）：点击灌装一次。 */
    private static ItemStack fillPane() {
        return pane(Material.CAULDRON, "§e点我装瓶",
                List.of(leg("§7在上方放入玻璃瓶后才能灌装")));
    }

    /**
     * 酒曲位展示：未加入时显示橙色占位板（提示往 13 号酒曲槽投放）；
     * 已加入时显示<b>该酒曲物品本身</b>（材质/头颅与指南中的酒曲一致）。
     */
    private static ItemStack yeastPane(WineCellarState st) {
        if (st.yeast() == null) {
            return pane(Material.ORANGE_STAINED_GLASS_PANE, "§6酒曲投放口",
                    List.of(leg("§7请将酒曲放在上面")));
        }
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(st.yeast());
        ItemStack yeast = sf != null ? sf.getItem().clone() : new ItemStack(Material.BARRIER);
        yeast.setAmount(1);
        ItemMeta meta = yeast.getItemMeta();
        if (meta != null) {
            String name = meta.hasDisplayName() ? meta.getDisplayName()
                    : ChatColor.WHITE + st.yeast();
            meta.setDisplayName("§a已放入酒曲！");
            meta.setLore(List.of(name, ChatColor.GRAY + "酿造模式下每台机器只能放入一种酒曲"));
            yeast.setItemMeta(meta);
        }
        return yeast;
    }

    private static void scanSlots(BlockMenu menu, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        boolean changed = false;
        // 运行中/已污染时不再吸收任何投入物（放入路径已在点击处理器拦截，这里兜底防御）：
        boolean acceptInput = st.phase() == WineCellarState.Phase.IDLE
                || st.phase() == WineCellarState.Phase.READY;

        // 酒曲投放位（槽 13）：只吸收酒曲，每台机器限一种，每次只消耗 1 个
        if (acceptInput && st.yeast() == null) {
            ItemStack it = menu.getItemInSlot(SLOT_YEAST_INPUT);
            if (it != null && !it.getType().isAir() && isYeast(it)) {
                st.yeast(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(it).getId());
                if (it.getAmount() > 1) {
                    it.setAmount(it.getAmount() - 1);
                    menu.replaceExistingItem(SLOT_YEAST_INPUT, it);
                } else {
                    menu.replaceExistingItem(SLOT_YEAST_INPUT, null);
                }
                changed = true;
            }
        }
        // 已加入过酒曲：槽 13 里多放的酒曲原样保留（不吞），由玩家自行取回

        // 出酒阶段：液位被灌装耗尽后整池复位（含清除酒曲记录）。否则酒曲会一直残留在机器上，
        // 下一轮既无法更换酒曲，也会让液位槽显示异常。
        if (st.phase() == WineCellarState.Phase.READY) {
            if (st.units() <= 0) {
                st.clear();
                changed = true;
            }
        }

        for (int slot : acceptInput ? INPUT_SLOTS : new int[0]) {
            ItemStack it = menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) continue;
            // 带 PDC 的容器优先判定：桶装果汁是水桶材质，必须先于清水判定，
            // 否则果汁桶会被当成清水吸收
            if (it.hasItemMeta()) {
                var pdc = it.getItemMeta().getPersistentDataContainer();
                // 陈酿果酒禁止回流：弹出到机器旁
                if (pdc.has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) {
                    drop(manager, it);
                    menu.replaceExistingItem(slot, null);
                    changed = true;
                    continue;
                }
                String contentsStr = pdc.get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
                if (contentsStr != null && !contentsStr.isEmpty()) {
                    if (st.hasAlcohol()) {
                        drop(manager, it);
                        menu.replaceExistingItem(slot, null);
                        changed = true;
                        continue;
                    }
                    boolean bucket = pdc.has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
                    int add = bucket ? 3 : 1;
                    if (!st.canAccept(add)) continue;
                    Map<String, Double> contents = JuicerRecipe.parseContentsFractional(contentsStr);
                    int sugarPerUnit = pdc.getOrDefault(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, 0);
                    String juicer = pdc.get(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING);
                    st.addLiquid(contents, sugarPerUnit, add, juicer);
                    // 每轮只吸收 1 个：整堆投入时不能直接把槽位换成 1 个空容器（会吞掉其余果汁）
                    if (it.getAmount() > 1) {
                        it.setAmount(it.getAmount() - 1);
                        menu.replaceExistingItem(slot, it);
                        giveContainer(menu, manager, bucket ? Material.BUCKET : Material.GLASS_BOTTLE);
                    } else {
                        menu.replaceExistingItem(slot, new ItemStack(bucket ? Material.BUCKET : Material.GLASS_BOTTLE));
                    }
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                    changed = true;
                    continue;
                }
            }
            // 清水：稀释（水瓶 +1 / 水桶 +3 单位，无糖分；返回空容器）
            if (isVanilla(it, Material.WATER_BUCKET)) {
                if (st.canAccept(3)) {
                    st.addWater(3);
                    menu.replaceExistingItem(slot, new ItemStack(Material.BUCKET));
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1f, 1f);
                    changed = true;
                }
                continue;
            }
            if (it.getType() == Material.POTION && isVanilla(it, Material.POTION) && isWaterBottle(it)) {
                if (st.canAccept(1)) {
                    st.addWater(1);
                    menu.replaceExistingItem(slot, new ItemStack(Material.GLASS_BOTTLE));
                    manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                    changed = true;
                }
                continue;
            }
            // 酒曲不允许走添料区：原样留着等玩家取回（点击拦截为主，这里防 shift 漏网）
            if (isYeast(it)) continue;
        }
        if (changed) st.save(manager);
    }

    /**
     * 出酒产物：锁定了酒窖配方时输出该配方的单位产物（带 PDC 数据），否则输出通用陈酿果酒。
     */
    private static ItemStack productItem(WineCellarState st, int perBottleSugar) {
        CellarRecipe cr = st.cellarRecipe() != null ? CellarRecipe.byKey(st.cellarRecipe()) : null;
        ItemStack out;
        if (cr == null) {
            out = WineBottle.create(st.alcohol(), perBottleSugar,
                    st.contentsOfAll(), st.allPlayers());
        } else {
            out = cr.result.clone();
            ItemMeta meta = out.getItemMeta();
            if (meta != null) {
                var pdc = meta.getPersistentDataContainer();
                pdc.set(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                        JuicerRecipe.joinContentsFractional(st.contentsOfAll()));
                if (st.allPlayers() != null && !st.allPlayers().isEmpty()) {
                    pdc.set(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                            String.join(",", st.allPlayers()));
                }
                pdc.set(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, perBottleSugar);
                if (cr.aging) {
                    pdc.set(JuicerRecipe.KEY_ITEM_ALCOHOL, PersistentDataType.DOUBLE, st.alcohol());
                }
                out.setItemMeta(meta);
            }
        }
        return appendCellarName(st, out);
    }

    /** 命名的酒窖灌装产物：在 lore 尾部追加酒窖名。 */
    private static ItemStack appendCellarName(WineCellarState st, ItemStack out) {
        if (st.cellarName() == null) return out;
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() != null
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("§6来自酒窖：§r" + st.cellarName());
            meta.setLore(lore);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 左下角命名牌按钮：显示当前名称与操作提示。 */
    private static ItemStack nameTagItem(WineCellarState st) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "酒窖命名");
            List<String> lore = new ArrayList<>();
            lore.add(st.cellarName() != null
                    ? ChatColor.GRAY + "当前名称: §r" + st.cellarName()
                    : ChatColor.GRAY + "当前未命名");
            lore.add(ChatColor.YELLOW + "点击为酒窖命名（支持颜色代码）");
            lore.add(ChatColor.DARK_GRAY + "仅放置酒窖的玩家可以命名");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 点击命名牌：校验开关与归属后，打开虚拟铁砧输入名字（点击「确定」即完成）。 */
    private static void clickNameTag(Player p, Block manager) {
        if (!com.haiman233.worldtaste.load.CellarLoader.cellarNameEnabled) {
            p.sendMessage("§c酒窖命名功能未开启！");
            return;
        }
        WineCellarState st = WineCellarState.get(manager);
        if (st.ownerId() != null && !st.isOwner(p)) {
            p.sendMessage("§c只有放置该酒窖的玩家才能命名！");
            return;
        }
        if (NAMING.containsKey(p.getUniqueId())) {
            p.sendMessage("§c你已打开命名界面！");
            return;
        }
        PENDING_CELLAR.put(p.getUniqueId(), manager.getLocation());
        p.sendMessage("§e请在铁砧上方输入框输入酒窖名字（支持 &6&l& 颜色代码&e），"
                + "点击右侧「§a§l确定§e」完成命名；点击左侧「§c取消命名§e」或直接关闭界面取消。");
        // 下个 tick 再开铁砧：当前正在处理酒窖页面的点击事件，立即切换界面可能被吞
        Bukkit.getScheduler().runTask(WT.plugin, () -> {
            if (!p.isOnline()) return;
            org.bukkit.inventory.InventoryView view = p.openAnvil(null, true);
            if (view == null || !(view.getTopInventory() instanceof org.bukkit.inventory.AnvilInventory ai)) {
                PENDING_CELLAR.remove(p.getUniqueId());
                p.sendMessage("§c无法打开命名界面！");
                return;
            }
            // 虚拟铁砧：免除重命名经验消耗
            try {
                ai.setMaximumRepairCost(0);
            } catch (Throwable ignored) {
                // 旧 API 无此方法时忽略
            }
            ai.setRepairCost(0);
            // 第一槽位：取消命名（点击即取消）
            org.bukkit.inventory.ItemStack cancelItem = new ItemStack(Material.PAPER);
            ItemMeta cm = cancelItem.getItemMeta();
            if (cm != null) {
                cm.setDisplayName("§c取消命名");
                List<String> lore = new ArrayList<>();
                lore.add("§7点击此物品取消命名");
                lore.add(st.cellarName() != null
                        ? "§7当前名称: §r" + st.cellarName() : "§7当前未命名");
                cm.setLore(lore);
                cancelItem.setItemMeta(cm);
            }
            ai.setItem(0, cancelItem);
            NAMING.put(p.getUniqueId(), ai);
            // 结果槽改名为「确定」（打字时会实时重算结果，用周期任务保持标签）
            final String confirmName = "§a§l确定";
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = Bukkit.getScheduler().runTaskTimer(WT.plugin, () -> {
                if (!p.isOnline() || NAMING.get(p.getUniqueId()) != ai
                        || p.getOpenInventory().getTopInventory() != ai) {
                    holder[0].cancel();
                    return;
                }
                org.bukkit.inventory.ItemStack result = ai.getItem(2);
                if (result == null || result.getType().isAir()) return;
                ItemMeta rm = result.getItemMeta();
                if (rm == null || (rm.hasDisplayName() && confirmName.equals(rm.getDisplayName()))) {
                    return;
                }
                rm.setDisplayName(confirmName);
                rm.setLore(List.of("§7点击确认命名"));
                result.setItemMeta(rm);
            }, 2L, 2L);
        });
    }

    /** 应用铁砧输入的酒窖名（主线程调用；名称为空视为取消）。 */
    private static void applyCellarName(Player p, Block manager, String msg) {
        if (msg.isEmpty() || msg.equalsIgnoreCase("cancel")) {
            p.sendMessage("§7已取消命名。");
            return;
        }
        if (msg.length() > 32) {
            p.sendMessage("§c名字过长（最多 32 字符）！请重新点击命名牌再试。");
            return;
        }
        WineCellarState st = WineCellarState.get(manager);
        st.setOwner(p); // 未记录归属的旧机器：首个命名者视为所有者
        // 竖线是存档分隔符，替换为空格防止破坏序列化
        String name = ChatColor.translateAlternateColorCodes('&', msg.replace('|', ' '));
        st.cellarName(name);
        st.save(manager);
        p.sendMessage("§a酒窖已命名为：§r" + name);
    }

    /** 点击炼药锅图标灌装一次：消耗灌装槽内 1 个玻璃瓶，果酒输出到输出槽。 */
    private static void fillOne(Player p, Block manager, BlockMenu menu) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c机器运行中，暂不可灌装！");
            return;
        }
        if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c液体已污染，请先清空机器！");
            return;
        }
        if (st.phase() != WineCellarState.Phase.READY || st.units() <= 0) {
            p.sendMessage("§c尚无可灌装的果酒（需先完成酿造/陈化）！");
            return;
        }
        ItemStack inSlot = menu.getItemInSlot(SLOT_FILL);
        if (inSlot == null || !isVanilla(inSlot, Material.GLASS_BOTTLE)) {
            p.sendMessage("§c缺少玻璃瓶！");
            return;
        }
        int out = firstEmpty(menu, OUTPUT_SLOTS);
        if (out < 0) {
            p.sendMessage("§c输出槽已满，无法继续灌装！");
            return;
        }
        int perBottleSugar = st.totalSugar() / Math.max(1, st.units());
        menu.replaceExistingItem(out, productItem(st, perBottleSugar));
        if (inSlot.getAmount() > 1) inSlot.setAmount(inSlot.getAmount() - 1);
        else menu.replaceExistingItem(SLOT_FILL, null);
        st.drainUnit();
        manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        if (st.units() <= 0) {
            st.clear();
            st.save(manager);
            p.sendMessage("§a酒窖已灌装完毕，已复原。");
            return;
        }
        st.save(manager);
    }

    private static int firstEmpty(BlockMenu menu, int[] slots) {
        for (int slot : slots) {
            ItemStack it = menu.getItemInSlot(slot);
            if (it == null || it.getType().isAir()) return slot;
        }
        return -1;
    }

    private static void drop(Block manager, ItemStack it) {
        manager.getWorld().dropItemNaturally(manager.getLocation().add(0.5, 1.2, 0.5), it);
    }

    /**
     * 返还果汁的空容器（玻璃瓶/铁桶）：优先塞进正在查看界面的玩家背包，
     * 背包满或无人查看时掉在机器旁，避免整堆投料时空容器无处可放被吞掉。
     */
    private static void giveContainer(BlockMenu menu, Block manager, Material m) {
        ItemStack empty = new ItemStack(m);
        for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(menu.getInventory().getViewers())) {
            if (!(viewer instanceof Player pl) || !pl.isOnline()) continue;
            for (ItemStack rest : pl.getInventory().addItem(empty).values()) {
                drop(manager, rest);
            }
            return;
        }
        drop(manager, empty);
    }

    // ===== 计时推进（管理器 ticker 供电成功后调用）=====

    static void advance(WineCellarState st, Block manager) {
        st.elapsedMs(st.elapsedMs() + TICK_MS);
        boolean finished = false;
        if (st.mode() == WineCellarState.Mode.BREW) {
            if (st.elapsedMs() >= st.durationMs()) {
                finishBrew(st, manager);
                finished = true;
            }
        } else {
            // 防御：异常数据（nextGrowthAt<=0）会导致while死循环，先校正
            if (st.nextGrowthAt() <= 0) st.nextGrowthAt(st.elapsedMs() + WineCellarState.GAME_DAY_MS);
            while (st.elapsedMs() >= st.nextGrowthAt()) {
                double g = JuicerRecipe.agingGrowthMin + ThreadLocalRandom.current().nextDouble()
                        * (JuicerRecipe.agingGrowthMax - JuicerRecipe.agingGrowthMin);
                st.alcohol(st.alcohol() * (1 + g));
                st.nextGrowthAt(st.nextGrowthAt() + WineCellarState.GAME_DAY_MS);
            }
        }
        // 每 5 分钟或酿造完成时落盘一次（计时中无需高频写 BlockStorage）
        if (finished || System.currentTimeMillis() - st.lastSaveMs() >= 300_000L) {
            st.save(manager);
        }
    }

    private static void finishBrew(WineCellarState st, Block manager) {
        // 酒精度 = 总糖分 × 转化比率 + 酒曲加成（清水不含糖分，稀释只摊薄产出份数）
        double yeast = JuicerRecipe.yeastBonus(st.yeast());
        CellarRecipe cr = st.cellarRecipe() != null ? CellarRecipe.byKey(st.cellarRecipe()) : null;
        if (cr != null && !cr.aging) {
            st.alcohol(0); // 该配方不允许陈酿/不含酒精（副产物）
        } else {
            st.alcohol(st.totalSugar() * JuicerRecipe.sugarAlcoholRatio + yeast);
        }
        st.yeast(null); // 酒曲随酿造消耗：完成后重置酒曲图标与记录
        manager.getWorld().playSound(manager.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
        if (st.autoAge() && st.durationMs() > 0) {
            // 自动陈化：酿造完成直接切到陈化模式开始计时（每完整游戏日增长）。
            // 测试模式（0 时长立即完成）跳过自动陈化，保证立即出产物。
            st.toggleMode();
            st.phase(WineCellarState.Phase.RUNNING);
            st.elapsedMs(0);
            st.nextGrowthAt(WineCellarState.GAME_DAY_MS);
            if (placer != null) placer.sendMessage("§a酿造已经完成！已自动开始陈化。");
        } else {
            st.phase(WineCellarState.Phase.READY);
            if (placer != null) {
                placer.sendMessage("§a酿造已经完成！耗时" + fmt(st.elapsedMs()) + "§a！");
            }
        }
    }

    private static void refreshAll() {
        Iterator<BlockMenu> it = MENUS.values().iterator();
        while (it.hasNext()) {
            BlockMenu menu = it.next();
            if (!menu.hasViewer()) continue;
            Block manager = menu.getBlock();
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager)) {
                it.remove();
                continue;
            }
            paint(menu, manager);
        }
    }

    private static final class CloseListener implements Listener {
        /**
         * 多方块结构被破坏（任意部分）→ 直接清除机器数据（液体/酒曲/配方/槽位内容物/
         * 归属与酒窖名一并清空），并同步清空打开中的界面槽位。
         * 快速预筛：只有破坏点周围存在酿造台时才深查。
         */
        @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onStructureBreak(BlockBreakEvent e) {
            Block b = e.getBlock();
            Material t = b.getType();
            if (t != Material.BREWING_STAND && t != Material.WAXED_COPPER_BLOCK
                    && t != Material.OAK_LOG && t != Material.OAK_PLANKS) return;
            Block manager = null;
            for (int dy = -1; dy <= 1 && manager == null; dy++) {
                for (int dz = -1; dz <= 1 && manager == null; dz++) {
                    for (int dx = -1; dx <= 1 && manager == null; dx++) {
                        Block m = b.getRelative(dx, dy, dz);
                        if (m.getType() != Material.BREWING_STAND) continue;
                        if (me.mrCookieSlime.Slimefun.api.BlockStorage.check(m) instanceof WineCellarManager
                                && CellarStructure.matches(m, false)) {
                            manager = m;
                        }
                    }
                }
            }
            if (manager == null) return;
            WineCellarManager.invalidateStructureCheck(manager);
            WineCellarState.get(manager).clearIdentity();
            WineCellarState.get(manager).save(manager);
            // 结构被破坏 → 机器数据一并清空，并移除内存缓存：原位重放新机器时从空状态重建
            WineCellarState.remove(manager);
            BlockMenu menu = MENUS.remove(manager.getLocation());
            if (menu != null) {
                for (int slot : INPUT_SLOTS) menu.replaceExistingItem(slot, null);
                for (int slot : OUTPUT_SLOTS) menu.replaceExistingItem(slot, null);
                menu.replaceExistingItem(SLOT_YEAST_INPUT, null);
                menu.replaceExistingItem(SLOT_FILL, null);
            }
        }

        /** 命名铁砧未确认就关闭：清理挂起状态（确认/取消路径已先行移除，此处兜底）。 */
        @EventHandler
        public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
            if (!(e.getPlayer() instanceof Player p)) return;
            if (NAMING.remove(p.getUniqueId()) != null) {
                PENDING_CELLAR.remove(p.getUniqueId());
            }
        }

        /** 玩家退出时清理命名流程的挂起状态。 */
        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
            NAMING.remove(e.getPlayer().getUniqueId());
            PENDING_CELLAR.remove(e.getPlayer().getUniqueId());
        }

        /** 记录酒窖归属（放置者，命名权限用）：延迟 1 tick 等粘液注册完方块数据。 */
        @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
            Block b = e.getBlockPlaced();
            if (b.getType() != Material.BREWING_STAND) return;
            Player placer = e.getPlayer();
            Bukkit.getScheduler().runTask(WT.plugin, () -> {
                if (b.getType() == Material.BREWING_STAND
                        && me.mrCookieSlime.Slimefun.api.BlockStorage.check(b) instanceof WineCellarManager) {
                    WineCellarState.get(b).setOwner(placer);
                }
            });
        }

        /** 命名铁砧：点击「确定」（产物槽）按输入名应用；点击「取消命名」（第一槽位）取消。 */
        @EventHandler
        public void onAnvilClick(InventoryClickEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            org.bukkit.inventory.Inventory anvil = NAMING.get(p.getUniqueId());
            if (anvil == null || e.getView().getTopInventory() != anvil) return;
            e.setCancelled(true);
            e.setCursor(null); // 防止结果物品被客户端预测带入光标
            if (e.getRawSlot() == 0) { // 取消命名
                NAMING.remove(p.getUniqueId());
                PENDING_CELLAR.remove(p.getUniqueId());
                p.closeInventory();
                p.sendMessage("§7已取消命名。");
                return;
            }
            if (e.getRawSlot() != 2) return; // 只认结果槽（确定）
            // 名字取铁砧改名框文本（结果槽已改名为「确定」，不能读物品名）
            String name = anvil instanceof org.bukkit.inventory.AnvilInventory ai
                    && ai.getRenameText() != null ? ai.getRenameText().trim() : null;
            NAMING.remove(p.getUniqueId());
            Location loc = PENDING_CELLAR.remove(p.getUniqueId());
            p.closeInventory();
            if (name == null || name.isEmpty()) {
                p.sendMessage("§7已取消命名（名称为空）。");
                return;
            }
            if (loc == null) return;
            applyCellarName(p, loc.getBlock(), name);
        }

        /**
         * Shift+右键清空：CSCoreLib 菜单点击不一定带上「右键」标记，因此在 Bukkit 事件里单独处理。
         * 同时统一接管从背包 shift 快速移入的路由（酒曲/果酒拦截，果汁/清水进添料区，玻璃瓶进灌装槽）。
         */
        @EventHandler(ignoreCancelled = false)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (!(e.getView().getTopInventory().getHolder() instanceof BlockMenu menu)) return;
            Block manager = menu.getBlock();
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager)) {
                return;
            }
            Inventory top = e.getView().getTopInventory();

            if (e.getClickedInventory() == top && e.getRawSlot() == SLOT_CLEAR
                    && e.isShiftClick() && e.isRightClick()) {
                e.setCancelled(true);
                clearContaminated(p, manager, true);
                paint(menu, manager);
                return;
            }

            if (e.getClickedInventory() == top && isOutputSlot(e.getRawSlot())) {
                ItemStack cursor = e.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !e.isShiftClick()) {
                    e.setCancelled(true);
                    p.sendMessage("§c输出槽不能放入物品！");
                    return;
                }
            }

            if (e.getClickedInventory() == top && isInputSlot(e.getRawSlot()) && isYeast(e.getCursor())) {
                e.setCancelled(true);
                p.sendMessage("§c酒曲请放入上方的「酒曲槽」（13 号）！");
                return;
            }

            if (e.isShiftClick() && e.getClickedInventory() != top) {
                // 从背包快速移入：原版会按槽位顺序落进酒曲槽/输出槽等错误位置，这里统一接管路由
                ItemStack moving = e.getCurrentItem();
                if (moving == null || moving.getType().isAir()) return;
                WineCellarState st = WineCellarState.get(manager);
                if (isYeast(moving)) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒曲请放入上方的「酒曲槽」（13 号）！");
                } else if (isWine(moving)) {
                    e.setCancelled(true);
                    p.sendMessage("§c陈酿果酒不能再次放入酒窖！");
                } else if (isJuice(moving) && st.hasAlcohol()) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒窖中已含酒精液体，不能投入果汁！");
                } else if (isJuice(moving) || isVanilla(moving, Material.WATER_BUCKET)
                        || (moving.getType() == Material.POTION
                            && isVanilla(moving, Material.POTION) && isWaterBottle(moving))) {
                    // 果汁/清水（水桶/水瓶）：统一引导进添料区，由扫描任务吸收
                    e.setCancelled(true);
                    if (st.phase() != WineCellarState.Phase.IDLE
                            && st.phase() != WineCellarState.Phase.READY) {
                        p.sendMessage("§c机器未处于待机/出酒状态，不能放入物品！");
                        return;
                    }
                    int dest = firstEmpty(menu, INPUT_SLOTS);
                    if (dest < 0) {
                        p.sendMessage("§c添料区已满，无法快速放入！");
                        return;
                    }
                    String reject = capacityReject(st, moving);
                    if (reject != null) {
                        p.sendMessage(reject);
                        return;
                    }
                    menu.replaceExistingItem(dest, moving.clone());
                    e.setCurrentItem(null);
                } else if (isVanilla(moving, Material.GLASS_BOTTLE)) {
                    // 玻璃瓶：直接进灌装槽（40 号），点击炼药锅图标即可装瓶
                    e.setCancelled(true);
                    if (st.phase() != WineCellarState.Phase.READY) {
                        p.sendMessage("§c只有出酒阶段才能放入玻璃瓶！");
                        return;
                    }
                    ItemStack inFill = menu.getItemInSlot(SLOT_FILL);
                    if (inFill != null && !inFill.getType().isAir()) {
                        p.sendMessage("§c灌装槽已有玻璃瓶！");
                        return;
                    }
                    menu.replaceExistingItem(SLOT_FILL, moving.clone());
                    e.setCurrentItem(null);
                } else {
                    e.setCancelled(true);
                    p.sendMessage("§c这个物品不能快速放入酒窖，请手持放入添料区！");
                }
            }
        }

        @EventHandler
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (!(e.getView().getTopInventory().getHolder() instanceof BlockMenu menu)) return;
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(menu.getBlock())
                    instanceof WineCellarManager)) return;
            ItemStack cursor = e.getOldCursor();
            boolean yeast = isYeast(cursor);
            boolean bottle = isVanilla(cursor, Material.GLASS_BOTTLE);
            if (!yeast && !bottle) return;
            for (int slot : e.getRawSlots()) {
                if (slot >= 54) continue;
                if (yeast && isInputSlot(slot)) {
                    e.setCancelled(true);
                    p.sendMessage("§c酒曲请放入上方的「酒曲槽」（13 号）！");
                    return;
                }
                if (bottle && isOutputSlot(slot)) {
                    e.setCancelled(true);
                    p.sendMessage("§c输出槽不能放入物品！");
                    return;
                }
            }
        }
    }

    private static boolean isInputSlot(int slot) {
        for (int s : INPUT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    private static boolean isOutputSlot(int slot) {
        for (int s : OUTPUT_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    // ===== 交互 =====

    private static void toggleMode(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c机器运行中，无法切换模式！");
            return;
        }
        if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c请先点击黄色玻璃板清空机器！");
            return;
        }
        st.toggleMode();
        st.save(manager);
    }

    /** 切换自动陈化（仅酿造模式、非运行态）：开启后酿造完成直接进入陈化。 */
    private static void toggleAutoAge(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING
                || st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c机器运行中，无法切换自动陈化！");
            return;
        }
        if (st.mode() != WineCellarState.Mode.BREW) {
            p.sendMessage("§c只有酿造模式下才能设置自动陈化！");
            return;
        }
        st.autoAge(!st.autoAge());
        st.save(manager);
        p.sendMessage(st.autoAge()
                ? "§a已开启自动陈化：酿造完成后自动开始陈化！"
                : "§7已关闭自动陈化。");
    }

    /**
     * 清空机器：普通点击仅处理污染态；{@code force=true}（<b>非运行态 shift+右键</b>）
     * 直接排空机内已有液体并复原，方便玩家中途换料重来。
     *
     * <p>注意：这里清的是<b>机器的液体与酒曲记录</b>，不是删除槽位里的物品堆——
     * 添料区/酒曲槽中玩家尚未被吸收的物品属于玩家物品，原样保留在槽位中。</p>
     */
    private static void clearContaminated(Player p, Block manager, boolean force) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c机器运行中，无法清空！");
            return;
        }
        boolean contaminated = st.phase() == WineCellarState.Phase.CONTAMINATED;
        if (st.units() <= 0 && st.yeast() == null && !contaminated) {
            if (force) p.sendMessage("§7酒窖内没有可清空的液体。");
            return;
        }
        if (!force && !contaminated) return;
        boolean hadYeast = st.yeast() != null;
        st.clear();
        st.save(manager);
        p.sendMessage(hadYeast ? "§a酒窖已清空复原，已一并清除酒曲。" : "§a酒窖已清空复原。");
    }

    private static void woolClick(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        switch (st.phase()) {
            case IDLE -> start(p, st, manager);
            case READY -> startAgeOrNothing(p, st, manager);
            case RUNNING -> {
                if (st.mode() == WineCellarState.Mode.BREW) {
                    st.contaminate();
                    p.sendMessage("§c液体全部报废！");
                    manager.getWorld().playSound(manager.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                } else {
                    st.phase(WineCellarState.Phase.READY);
                    p.sendMessage("§a陈化已停止，可放置玻璃瓶出酒。");
                }
                st.save(manager);
            }
            default -> { }
        }
    }

    private static void start(Player p, WineCellarState st, Block manager) {
        if (!CellarStructure.matches(manager, false)) {
            p.sendMessage("§c多方块结构不完整，无法启动酿造！");
            return;
        }
        if (!hasEnoughPower(manager)) {
            p.sendMessage("§c电量不足！");
            return;
        }
        if (st.yeast() == null) {
            p.sendMessage("§c必须加入一种酒曲才能开始配方！");
            return;
        }
        if (st.juiceUnits() <= 0) {
            p.sendMessage("§c盆中只有清水，需要先投入果汁才能酿造！");
            return;
        }
        if (st.mode() == WineCellarState.Mode.AGE) {
            startAgeOrNothing(p, st, manager);
            return;
        }
        // 按材料份数匹配：池内各原料总份数（每单位果汁按组成折算材料数，如 1 单位
        // 甜浆果汁 = 3 份甜浆果），与配方 ingredient 数量成整数倍比例时优先按配方产出
        // （清水不参与匹配）
        Map<String, Double> portions = new LinkedHashMap<>();
        for (WineCellarState.Liquid lq : st.liquids()) {
            for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                portions.merge(e.getKey(), e.getValue() * lq.units(), Double::sum);
            }
        }
        CellarRecipe.MatchResult cm = CellarRecipe.match(portions);
        // 配方批次豁免 8 单位最低液位（1 倍配方可能只有几单位）；通用果酒仍需 ≥8
        if (cm == null && st.units() < 8) {
            p.sendMessage("§c液位不足 8 单位，无法启动！");
            return;
        }
        // 【临时测试】酿造时长占位符已移除：启动后下一个机器 tick 即酿造完成
        // （正式版恢复：st.startRun(p, (20 + ThreadLocalRandom.current().nextInt(21)) * 60_000L);）
        st.startRun(p, 0L);
        st.setCellarRecipe(cm != null ? cm.recipe.key : null, cm != null ? cm.multiplier : 0);
        st.save(manager);
        p.sendMessage("§a酿造开始！（测试模式：立即完成）"
                + (cm != null ? " §7配方: §e" + cm.recipe.key
                        + " §7(×" + cm.multiplier + "，产出 §e" + st.juiceUnits() + " 单位§7)"
                        : ""));
    }

    private static void startAgeOrNothing(Player p, WineCellarState st, Block manager) {
        if (st.mode() != WineCellarState.Mode.AGE) return;
        if (!CellarStructure.matches(manager, false)) {
            p.sendMessage("§c多方块结构不完整，无法开始陈化！");
            return;
        }
        if (!hasEnoughPower(manager)) {
            p.sendMessage("§c电量不足！");
            return;
        }
        if (!st.hasAlcohol()) {
            p.sendMessage("§c没有含酒精的液体，无法陈化！");
            return;
        }
        st.startRun(p, 0);
        st.nextGrowthAt(WineCellarState.GAME_DAY_MS);
        st.save(manager);
        p.sendMessage("§a陈化开始！每完整游戏日酒精度按配置比例增长。");
    }

    // ===== 展示物品 =====

    private static ItemStack clockItem(WineCellarState st) {
        ItemStack it = new ItemStack(Material.CLOCK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(st.mode() == WineCellarState.Mode.BREW
                    ? ChatColor.GOLD + "酿造模式" : ChatColor.AQUA + "陈化模式");
            List<String> lore = new ArrayList<>();
            if (st.phase() == WineCellarState.Phase.RUNNING) {
                if (st.mode() == WineCellarState.Mode.BREW) {
                    lore.add(ChatColor.GRAY + "剩余: " + ChatColor.YELLOW
                            + fmt(Math.max(0, st.durationMs() - st.elapsedMs())));
                } else {
                    // 只按完整游戏日计数：不足 1 日的零头不计入陈化天数、也不触发酒精度增长
                    long days = st.elapsedMs() / WineCellarState.GAME_DAY_MS;
                    lore.add(ChatColor.GRAY + "已陈化: " + ChatColor.YELLOW + days + ChatColor.GRAY + " 游戏日");
                    lore.add(ChatColor.DARK_GRAY + "不满一个完整游戏日不计入");
                }
                lore.add(ChatColor.GRAY + "电力不足时酿造/陈化直接失败！");
            } else {
                lore.add(ChatColor.GRAY + "当前未运行");
            }
            lore.add("");
            lore.add(st.mode() == WineCellarState.Mode.BREW
                    ? ChatColor.GRAY + "酿造: 糖分×比率+酒曲 → 酒精度，20~40 分钟"
                    : ChatColor.GRAY + "陈化: 每完整游戏日酒精度按比例增长");
            if (st.mode() == WineCellarState.Mode.BREW) {
                lore.add(st.autoAge()
                        ? ChatColor.GREEN + "自动陈化: 已开启（酿造完成后自动陈化）"
                        : ChatColor.GRAY + "自动陈化: 已关闭");
                lore.add(ChatColor.YELLOW + "Shift+右键切换自动陈化！");
            }
            lore.add(ChatColor.GRAY + "需要含酒精液体才能陈化");
            lore.add("");
            lore.add(ChatColor.YELLOW + "点击切换模式");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack clearItem(WineCellarState st) {
        boolean contaminated = st.phase() == WineCellarState.Phase.CONTAMINATED;
        ItemStack it = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            if (st.phase() == WineCellarState.Phase.RUNNING) {
                meta.setDisplayName(ChatColor.YELLOW + "污染清空按钮");
                lore.add(ChatColor.RED + "机器运行中，无法清空");
            } else {
                meta.setDisplayName(ChatColor.YELLOW + (contaminated ? "清空机器（复原）" : "污染清空按钮"));
                lore.add(contaminated ? ChatColor.GRAY + "点击清空污染液体并复原机器"
                        : ChatColor.DARK_GRAY + "液体报废后用于复原机器");
                lore.add(ChatColor.YELLOW + "Shift + 右键：直接清空机内液体");
                lore.add(ChatColor.DARK_GRAY + "（一并清除已放入的酒曲）");
            }
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack woolItem(WineCellarState st) {
        Material m = st.phase() == WineCellarState.Phase.RUNNING ? Material.LIME_WOOL : Material.RED_WOOL;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            switch (st.phase()) {
                case IDLE -> {
                    meta.setDisplayName(ChatColor.RED + "启动");
                    lore.add(ChatColor.GRAY + "液位: " + st.units() + "/" + WineCellarState.CAPACITY
                            + "（通用 ≥8，配方不限）");
                    lore.add(st.yeast() == null ? ChatColor.RED + "未加入酒曲" : ChatColor.GREEN + "酒曲已加入");
                    lore.add(ChatColor.GRAY + "模式: " + (st.mode() == WineCellarState.Mode.BREW ? "酿造" : "陈化"));
                    if (st.mode() == WineCellarState.Mode.AGE) {
                        lore.add(ChatColor.RED + "陈化需要含酒精的液体");
                    }
                    lore.add(ChatColor.YELLOW + "点击启动");
                }
                case RUNNING -> {
                    meta.setDisplayName(ChatColor.GREEN + (st.mode() == WineCellarState.Mode.BREW
                            ? "酿造中 · 点击关闭" : "陈化中 · 点击停止"));
                    lore.add(ChatColor.GRAY + "正在耗电运行（断电=直接失败报废）");
                    if (st.mode() == WineCellarState.Mode.BREW) {
                        lore.add(ChatColor.RED + "酿造中关闭将报废全部液体！");
                    } else {
                        lore.add(ChatColor.GRAY + "关闭后停止计时，可出酒");
                    }
                }
                case READY -> {
                    meta.setDisplayName(ChatColor.GREEN + "已完成 · 下方槽位放玻璃瓶灌装");
                    lore.add(ChatColor.GRAY + "酒精度: " + fmt(st.alcohol()) + "°  液位: " + st.units() + " 单位");
                    if (st.mode() == WineCellarState.Mode.BREW) {
                        lore.add(ChatColor.GRAY + "切换陈化模式后点击羊毛可开始陈化");
                    }
                }
                default -> meta.setDisplayName(ChatColor.DARK_RED + "液体已报废");
            }
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack pane(Material m, String name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component leg(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private static String fmt(double alcohol) {
        return String.format("%.1f", alcohol);
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + "分" + (sec % 60) + "秒";
    }

    private static boolean isVanilla(ItemStack item, Material m) {
        return item != null && item.getType() == m
                && io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item) == null;
    }
}
