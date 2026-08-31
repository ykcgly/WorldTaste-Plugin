package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 酒窖管理器机器页面：槽 4 时钟（模式/计时，点击切换）、槽 7 黄板（污染清空；
 * <b>非运行态 shift+右键可直接清空机内液体</b>）、右列粉色液位线（24 单位，1 格 4 单位）、
 * <b>添料区 3×3 空槽</b> 19-21/28-30/37-39 投入（瓶 +1/桶 +3，返还空容器；
 * 陈化完成后放入<b>原版玻璃瓶</b>即自动灌装，成品输出到出酒区）、
 * <b>槽 13 酒曲放置位</b>（只接受酒曲，投入后<b>只消耗 1 个</b>并记录到槽 22 展示，限一种）、
 * 槽 31 羊毛启动/关闭（酿造关闭=报废、陈化关闭=出酒；运行时黄绿）、
 * <b>出酒区 3×3 空槽</b> 23-25/32-34/41-43 <b>只出不进</b>（承接灌装成品，不再接受玻璃瓶）、
 * 槽 40 产物预览、槽 49 炼药锅「灌装槽」标识（非玩家槽，不可存取）。
 * 由 10 tick 周期任务扫描槽位并实时刷新；计时推进由管理器 ticker 调用 advance。
 *
 * <p><b>槽位分区约定</b>：{@link #INPUT_SLOTS} / {@link #OUTPUT_SLOTS} / {@link #SLOT_YEAST_INPUT}
 * 属于「玩家槽」，构建时不放任何物品也不放背景（真正留空），否则背景玻璃板会占位——
 * 玩家既放不进东西，关闭界面时还会被 {@link #returnItems} 当成物品返还给玩家（刷玻璃板）。</p>
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
    private static final int SLOT_OUTPUT_PREVIEW = 40;
    private static final int[] OUTPUT_SLOTS = {23, 24, 25, 32, 33, 34, 41, 42, 43};
    /** 灌装槽标识（炼药锅，非玩家槽，仅作功能说明）。 */
    private static final int SLOT_FILL = 49;
    private static final int[] GREEN_DECO = {10, 11, 12, 18, 27, 36, 46, 47, 48};
    private static final int[] RED_DECO = {14, 15, 16, 50, 51, 52};
    private static final long TICK_MS = 50;

    private static final Map<UUID, Location> SESSIONS = new HashMap<>();
    private static BukkitTask refreshTask;

    private CellarMenu() {}

    public static void register() {
        refreshTask = Bukkit.getScheduler().runTaskTimer(WT.plugin, CellarMenu::refreshAll, 10, 10);
        Bukkit.getPluginManager().registerEvents(new CloseListener(), WT.plugin);
    }

    public static void open(Player p, Block manager) {
        ChestMenu menu = build(manager);
        SESSIONS.put(p.getUniqueId(), manager.getLocation());
        menu.open(p);
        repaint(p, manager);
    }

    private static ChestMenu build(Block manager) {
        ChestMenu menu = new ChestMenu(ChatColor.GOLD + "酒窖管理器");
        menu.setEmptySlotsClickable(true);
        menu.setPlayerInventoryClickable(true);

        WineCellarState st = WineCellarState.get(manager);

        // 背景只覆盖「非玩家槽位」：添料区/出酒区/暂存槽一律留空。
        // 若给这些槽放背景玻璃板，玩家既看不到空槽也放不进东西，关闭时背景板还会被当成物品返还。
        for (int i = 0; i < 54; i++) {
            if (isPlayerSlot(i)) continue;
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }

        // 三个控制按钮：可点击（时钟切换模式 / 黄板清空污染 / 羊毛启动停止）
        menu.addItem(SLOT_CLOCK, clockItem(st), (pl, s, cur, action) -> {
            toggleMode(pl, manager);
            repaint(pl, manager);
            return false;
        });
        // 清空按钮：普通点击仅在污染态生效；非运行态 shift+右键 = 强制清空机内液体
        menu.addItem(SLOT_CLEAR, clearItem(st), new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                boolean shiftRight =
                        (e != null && e.isRightClick() && e.isShiftClick())
                                || (action != null && action.isShiftClicked() && action.isRightClicked());
                clearContaminated(pl, manager, shiftRight);
                repaint(pl, manager);
                return false;
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                // 已注册为 AdvancedMenuClickHandler，MenuListener 只走上面的重载
                return false;
            }
        });
        menu.addItem(SLOT_WOOL, woolItem(st), (pl, s, cur, action) -> {
            woolClick(pl, manager);
            repaint(pl, manager);
            return false;
        });

        // 酒曲展示位（非玩家槽）：只读展示已加入的酒曲
        menu.addItem(SLOT_YEAST, yeastPane(st), ChestMenuUtils.getEmptyClickHandler());
        // 灌装槽标识（非玩家槽）：炼药锅
        menu.addItem(SLOT_FILL, fillPane(st), ChestMenuUtils.getEmptyClickHandler());

        // 装饰文本（绿/红玻璃板标识）
        for (int slot : GREEN_DECO) {
            menu.addItem(slot, pane(Material.LIME_STAINED_GLASS_PANE, "§a添料区",
                    List.of(leg("§7添料槽：投入瓶装/桶装果汁"), leg("§7瓶 +1 单位，桶 +3 单位"),
                            leg("§7出酒阶段：放入原版玻璃瓶自动灌装"),
                            leg("§7成品自动输出到右侧出酒区"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }
        for (int slot : RED_DECO) {
            menu.addItem(slot, pane(Material.RED_STAINED_GLASS_PANE, "§c出酒区",
                    List.of(leg("§7出酒槽：承接灌装完成的成品"), leg("§7空手点击即可取走成品"))),
                    ChestMenuUtils.getEmptyClickHandler());
        }

        // 添料区：果汁投料 + 出酒阶段玻璃瓶灌装；禁止果酒回流，酒曲只能在 13 号槽投放
        for (int slot : INPUT_SLOTS) {
            menu.addMenuClickHandler(slot, new ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                       ClickAction action) {
                    // 只拦「放入」：光标上有物品才算放入；空手提起、shift 取出一律放行。
                    // （从玩家背包 shift 快速移入走的不是本处理器，由 scanSlots 兜底拦截）
                    ItemStack incoming = (cursor != null && !cursor.getType().isAir()) ? cursor : null;
                    if (isWine(incoming)) {
                        pl.sendMessage("§c陈酿果酒不能再次放入酒窖！");
                        return false;
                    }
                    if (isYeast(incoming)) {
                        pl.sendMessage("§c酒曲请放入上方的「酒曲槽」！");
                        return false;
                    }
                    return true;
                }

                @Override
                public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                    // 已注册为 AdvancedMenuClickHandler，MenuListener 只走上面的重载
                    return true;
                }
            });
        }
        // 出酒区：只出不进（成品由灌装槽自动输出到此处，不再接受玩家放入玻璃瓶）
        for (int slot : OUTPUT_SLOTS) {
            menu.addMenuClickHandler(slot, new ChestMenu.AdvancedMenuClickHandler() {
                @Override
                public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                       ClickAction action) {
                    if (action != null && action.isShiftClicked()) return true;
                    if (cursor == null || cursor.getType().isAir()) return true;
                    pl.sendMessage("§c出酒区只出不进，玻璃瓶请放入左侧添料区！");
                    return false;
                }

                @Override
                public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                    return true;
                }
            });
        }
        // 酒曲投放位：只接受酒曲，且每台机器限一种（投入后只消耗 1 个）
        menu.addMenuClickHandler(SLOT_YEAST_INPUT, new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(InventoryClickEvent e, Player pl, int s, ItemStack cursor,
                                   ClickAction action) {
                if (action != null && action.isShiftClicked()) return true;
                if (cursor == null || cursor.getType().isAir()) return true;
                if (!isYeast(cursor)) {
                    pl.sendMessage("§c酒曲槽只能放入酒曲！");
                    return false;
                }
                if (WineCellarState.get(manager).yeast() != null) {
                    pl.sendMessage("§c该酒窖已加入过酒曲！");
                    return false;
                }
                return true;
            }

            @Override
            public boolean onClick(Player pl, int s, ItemStack cur, ClickAction action) {
                return true;
            }
        });
        return menu;
    }

    /** 玩家可自由存取的槽位（构建时不放背景，真正留空）。 */
    private static boolean isPlayerSlot(int slot) {
        for (int s : INPUT_SLOTS) {
            if (s == slot) return true;
        }
        for (int s : OUTPUT_SLOTS) {
            if (s == slot) return true;
        }
        return slot == SLOT_YEAST_INPUT;
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

    private static void repaint(Player p, Block manager) {
        if (!p.isOnline()) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getSize() < 54) return;
        paint(top, manager);
    }

    private static void paint(Inventory top, Block manager) {
        scanSlots(top, manager);
        WineCellarState st = WineCellarState.get(manager);
        top.setItem(SLOT_CLOCK, clockItem(st));
        top.setItem(SLOT_CLEAR, clearItem(st));
        top.setItem(SLOT_WOOL, woolItem(st));
        int filled = st.phase() == WineCellarState.Phase.CONTAMINATED
                ? GAUGE.length : (int) Math.ceil(st.units() / 4.0);
        int idx = 0;
        for (int i = GAUGE.length - 1; i >= 0; i--) {
            int slot = GAUGE[i];
            boolean full = idx < filled;
            idx++;
            if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
                top.setItem(slot, pane(Material.BLACK_STAINED_GLASS_PANE, "§8受污染的液体",
                        List.of(leg("§7点击黄色玻璃板清空复原"))));
                continue;
            }
            if (full && !st.liquids().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                lore.add(leg("§7液位: §e" + st.units() + "§7/" + WineCellarState.CAPACITY + " 单位"));
                if (st.hasAlcohol()) lore.add(leg("§c酒精度: §e" + fmt(st.alcohol()) + "°"));
                for (WineCellarState.Liquid lq : st.liquids()) {
                    lore.add(leg("§b" + contentsText(lq.contents()) + " §7×" + lq.units()
                            + " §d糖分" + lq.sugarPerUnit() + " §8榨汁师: §f"
                            + String.join("、", lq.players())));
                }
                top.setItem(slot, pane(Material.PINK_STAINED_GLASS_PANE, "§d液位线", lore));
            } else {
                int free = Math.max(0, WineCellarState.CAPACITY - (full ? filled * 4 : st.units()));
                top.setItem(slot, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7液位线（空）",
                        List.of(leg("§7空余容量: §e" + free + "§7 单位"))));
            }
        }
        paintYeasts(top, st);
        top.setItem(SLOT_FILL, fillPane(st));
    }

    private static void paintYeasts(Inventory top, WineCellarState st) {
        top.setItem(SLOT_YEAST, yeastPane(st));
        if (st.phase() == WineCellarState.Phase.READY && st.units() > 0) {
            top.setItem(SLOT_OUTPUT_PREVIEW, WineBottle.preview(st.alcohol(),
                    st.totalSugar() / Math.max(1, st.units()), st.contentsOfAll(), st.allPlayers()));
        } else {
            top.setItem(SLOT_OUTPUT_PREVIEW, ChestMenuUtils.getBackground());
        }
    }

    /**
     * 槽 49 灌装槽标识（炼药锅）：说明「在上方添料区放入玻璃瓶自动灌装，成品输出到出酒区」，
     * 并按当前相位提示是否可灌装。不可存取，纯功能展示。
     */
    private static ItemStack fillPane(WineCellarState st) {
        List<Component> lore = new ArrayList<>();
        lore.add(leg("§7在上方槽位放入玻璃瓶自动灌装"));
        lore.add(leg(""));
        if (st.phase() == WineCellarState.Phase.READY && st.units() > 0) {
            lore.add(leg("§a当前可灌装：剩余 §e" + st.units() + "§a 单位"));
        } else if (st.phase() == WineCellarState.Phase.RUNNING) {
            lore.add(leg("§c机器运行中，暂不可灌装"));
        } else if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            lore.add(leg("§c液体已污染，请清空后重新酿造"));
        } else {
            lore.add(leg("§7尚无成品，酒液陈酿完成后方可灌装"));
        }
        return pane(Material.CAULDRON, "§e灌装槽", lore);
    }

    /**
     * 酒曲位展示：未加入时显示橙色占位板（提示往上方 13 号酒曲槽投放）；
     * 已加入时显示<b>该酒曲实物</b>，名字改为「已放入酒曲！」，lore 为酒曲本身的名称。
     * 酿造模式下每台机器只认一种酒曲——已加入后 {@link #scanSlots} 停止吸收。
     */
    private static ItemStack yeastPane(WineCellarState st) {
        if (st.yeast() == null) {
            return pane(Material.ORANGE_STAINED_GLASS_PANE, "§6酒曲槽",
                    List.of(leg("§7在上方空位放入酒曲")));
        }
        ItemStack yeast = JuicerRecipe.refToItem(st.yeast());
        yeast.setAmount(1);
        ItemMeta meta = yeast.getItemMeta();
        if (meta != null) {
            String name = meta.hasDisplayName() ? meta.getDisplayName()
                    : ChatColor.WHITE + yeast.getType().name();
            meta.setDisplayName("§a已放入酒曲！");
            meta.lore(List.of(leg(name), leg("§7酿造模式下每台机器只能放入一种酒曲")));
            yeast.setItemMeta(meta);
        }
        return yeast;
    }

    private static void scanSlots(Inventory top, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        boolean changed = false;
        // 运行中/已污染时不再吸收任何投入物：物品原样留在空槽里，关闭界面时统一返还，
        // 与右键投放（deposit）的「机器运行中，不能再放入任何物品」保持一致，避免静默吞物。
        boolean acceptInput = st.phase() == WineCellarState.Phase.IDLE
                || st.phase() == WineCellarState.Phase.READY;

        // 酒曲投放位（槽 13）：只吸收酒曲，每台机器限一种，每次只消耗 1 个
        if (acceptInput && st.yeast() == null) {
            ItemStack it = top.getItem(SLOT_YEAST_INPUT);
            if (it != null && !it.getType().isAir() && isYeast(it)) {
                st.yeast(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(it).getId());
                if (it.getAmount() > 1) {
                    it.setAmount(it.getAmount() - 1);
                    top.setItem(SLOT_YEAST_INPUT, it);
                } else {
                    top.setItem(SLOT_YEAST_INPUT, null);
                }
                changed = true;
            }
        }
        // 已加入过酒曲：槽 13 里的酒曲原样保留（不吞、不丢弃），由玩家自行取回

        // 出酒阶段：添料区的原版玻璃瓶自动灌装，成品输出到出酒区
        if (st.phase() == WineCellarState.Phase.READY) {
            changed |= fillBottles(top, manager, st);
            // 出酒完毕：整池复位（含清除酒曲记录）。否则酒曲会一直残留在机器上，
            // 下一轮既无法更换酒曲，也会让液位槽显示异常。
            if (st.units() <= 0) {
                st.clear();
                changed = true;
            }
        }

        for (int slot : acceptInput ? INPUT_SLOTS : new int[0]) {
            ItemStack it = top.getItem(slot);
            if (it == null || it.getType().isAir() || !it.hasItemMeta()) continue;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            if (pdc.has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) {
                drop(manager, it);
                top.setItem(slot, null);
                changed = true;
                continue;
            }
            String contentsStr = pdc.get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
            if (contentsStr != null && !contentsStr.isEmpty()) {
                if (st.hasAlcohol()) {
                    drop(manager, it);
                    top.setItem(slot, null);
                    changed = true;
                    continue;
                }
                boolean bucket = pdc.has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
                int add = bucket ? 3 : 1;
                if (!st.canAccept(add)) continue;
                Map<String, Integer> contents = JuicerRecipe.parseContents(contentsStr);
                int sugarPerUnit = pdc.getOrDefault(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, 0);
                String juicer = pdc.get(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING);
                st.addLiquid(contents, sugarPerUnit, add, juicer);
                // 每轮只吸收 1 个：整堆投入时不能直接把槽位换成 1 个空容器（会吞掉其余果汁）
                if (it.getAmount() > 1) {
                    it.setAmount(it.getAmount() - 1);
                    top.setItem(slot, it);
                    giveContainer(top, manager, bucket ? Material.BUCKET : Material.GLASS_BOTTLE);
                } else {
                    top.setItem(slot, new ItemStack(bucket ? Material.BUCKET : Material.GLASS_BOTTLE));
                }
                manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                changed = true;
            }
        }
        if (changed) st.save(manager);
    }

    /**
     * 灌装：出酒阶段在添料区放入<b>原版玻璃瓶</b>，每瓶消耗 1 单位液体，
     * 成品直接输出到出酒区空位。出酒区已满时保留玻璃瓶（不吞也不灌），等玩家取走成品后继续。
     */
    private static boolean fillBottles(Inventory top, Block manager, WineCellarState st) {
        boolean changed = false;
        for (int slot : INPUT_SLOTS) {
            if (st.units() <= 0) break;
            ItemStack it = top.getItem(slot);
            if (it == null || !isVanilla(it, Material.GLASS_BOTTLE)) continue;
            int out = firstEmpty(top, OUTPUT_SLOTS);
            if (out < 0) continue;
            int perBottleSugar = st.totalSugar() / Math.max(1, st.units());
            top.setItem(out, WineBottle.create(st.alcohol(), perBottleSugar,
                    st.contentsOfAll(), st.allPlayers()));
            st.drainUnit();
            if (it.getAmount() > 1) {
                it.setAmount(it.getAmount() - 1);
                top.setItem(slot, it);
            } else {
                top.setItem(slot, null);
            }
            manager.getWorld().playSound(manager.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
            changed = true;
        }
        return changed;
    }

    /** 返回给定槽位中第一个空位，全部占用时返回 -1。 */
    private static int firstEmpty(Inventory top, int[] slots) {
        for (int slot : slots) {
            ItemStack it = top.getItem(slot);
            if (it == null || it.getType().isAir()) return slot;
        }
        return -1;
    }

    private static void returnItems(Player p, Inventory top) {
        for (int[] group : new int[][]{INPUT_SLOTS, OUTPUT_SLOTS, {SLOT_YEAST_INPUT}}) {
            for (int slot : group) {
                ItemStack it = top.getItem(slot);
                if (it != null && !it.getType().isAir()) {
                    p.getInventory().addItem(it).values()
                            .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
                    top.setItem(slot, null);
                }
            }
        }
    }

    private static void drop(Block manager, ItemStack it) {
        manager.getWorld().dropItemNaturally(manager.getLocation().add(0.5, 1.2, 0.5), it);
    }

    /**
     * 返还果汁的空容器（玻璃瓶/铁桶）：优先塞进正在查看界面的玩家背包，
     * 背包满或无人查看时掉在机器旁，避免整堆投料时空容器无处可放被吞掉。
     */
    private static void giveContainer(Inventory top, Block manager, Material m) {
        ItemStack empty = new ItemStack(m);
        for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(top.getViewers())) {
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
        if (st.mode() == WineCellarState.Mode.BREW) {
            if (st.elapsedMs() >= st.durationMs()) {
                finishBrew(st, manager);
            }
        } else {
            while (st.elapsedMs() >= st.nextGrowthAt()) {
                double g = JuicerRecipe.agingGrowthMin + ThreadLocalRandom.current().nextDouble()
                        * (JuicerRecipe.agingGrowthMax - JuicerRecipe.agingGrowthMin);
                st.alcohol(st.alcohol() * (1 + g));
                st.nextGrowthAt(st.nextGrowthAt() + WineCellarState.GAME_DAY_MS);
            }
        }
        st.save(manager);
    }

    private static void finishBrew(WineCellarState st, Block manager) {
        st.alcohol(JuicerRecipe.totalSugar(st.contentsOfAll()) * JuicerRecipe.sugarAlcoholRatio
                + JuicerRecipe.yeastBonus(st.yeast()));
        st.phase(WineCellarState.Phase.READY);
        manager.getWorld().playSound(manager.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
        if (placer != null) {
            placer.sendMessage("§a酿造已经完成！耗时" + fmt(st.elapsedMs()) + "§a！");
        }
    }

    private static void refreshAll() {
        for (Map.Entry<UUID, Location> en : new ArrayList<>(SESSIONS.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) {
                SESSIONS.remove(en.getKey());
                continue;
            }
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getSize() < 54) continue;
            Block manager = en.getValue().getBlock();
            if (!(me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager)) {
                SESSIONS.remove(en.getKey());
                continue;
            }
            paint(top, manager);
        }
    }

    private static final class CloseListener implements Listener {
        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (!(e.getPlayer() instanceof Player p)) return;
            Location loc = SESSIONS.remove(p.getUniqueId());
            if (loc != null) {
                Block manager = loc.getBlock();
                if (me.mrCookieSlime.Slimefun.api.BlockStorage.check(manager) instanceof WineCellarManager) {
                    returnItems(p, e.getInventory());
                }
            }
        }
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

    /**
     * 清空机器：普通点击仅处理污染态；{@code force=true}（<b>非运行态 shift+右键</b>）
     * 直接排空机内已有液体并复原，方便玩家中途换料重来。
     *
     * <p>注意：这里清的是<b>机器的液体与酒曲记录</b>，不是删除槽位里的物品堆——
     * 添料区/酒曲槽中玩家尚未被吸收的物品属于玩家物品，原样保留，关闭界面时照常返还。</p>
     */
    private static void clearContaminated(Player p, Block manager, boolean force) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c机器运行中，无法清空！");
            return;
        }
        boolean contaminated = st.phase() == WineCellarState.Phase.CONTAMINATED;
        if (st.units() <= 0 && st.yeast() == null && !contaminated) {
            if (force) p.sendMessage("§7机器内没有可清空的液体。");
            return;
        }
        if (!force && !contaminated) return;
        boolean hadYeast = st.yeast() != null;
        st.clear();
        st.save(manager);
        p.sendMessage(hadYeast ? "§a机器已清空复原，已一并清除酒曲。" : "§a机器已清空复原。");
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
        if (st.units() < 8) {
            p.sendMessage("§c液位不足 8 单位，无法启动！");
            return;
        }
        if (st.yeast() == null) {
            p.sendMessage("§c必须加入一种酒曲才能开始配方！");
            return;
        }
        if (st.mode() == WineCellarState.Mode.AGE) {
            startAgeOrNothing(p, st, manager);
            return;
        }
        // 【临时测试】酿造时长占位符已移除：启动后下一个机器 tick 即酿造完成
        // （正式版恢复：st.startRun(p, (20 + ThreadLocalRandom.current().nextInt(21)) * 60_000L);）
        st.startRun(p, 0L);
        st.save(manager);
        p.sendMessage("§a酿造开始！（测试模式：立即完成）");
    }

    private static void startAgeOrNothing(Player p, WineCellarState st, Block manager) {
        if (st.mode() != WineCellarState.Mode.AGE) return;
        if (!st.hasAlcohol()) {
            p.sendMessage("§c没有含酒精的液体，无法陈化！");
            return;
        }
        st.startRun(p, 0);
        st.nextGrowthAt(WineCellarState.GAME_DAY_MS);
        st.save(manager);
        p.sendMessage("§a陈化开始！每游戏日（24 分钟）酒精度按配置比例增长。");
    }

    private static void deposit(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() == WineCellarState.Phase.RUNNING) {
            p.sendMessage("§c机器运行中，不能再放入任何物品！");
            return;
        }
        if (st.phase() == WineCellarState.Phase.CONTAMINATED) {
            p.sendMessage("§c液体已污染，请点击黄色玻璃板清空机器！");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || !hand.hasItemMeta()) return;
        var pdc = hand.getItemMeta().getPersistentDataContainer();
        if (pdc.has(JuicerRecipe.KEY_ITEM_WINE, PersistentDataType.BYTE)) {
            p.sendMessage("§c陈酿果酒不能再次放入酒窖！");
            return;
        }
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(hand);
        if (sf != null && JuicerRecipe.yeastBonus(sf.getId()) >= 0) {
            if (st.yeast() != null) {
                p.sendMessage("§c该酒窖已加入过酒曲！");
                return;
            }
            consumeOne(p);
            st.yeast(sf.getId());
            st.save(manager);
            p.sendMessage("§a已加入酒曲: §e" + sf.getId());
            return;
        }
        String contentsStr = pdc.get(JuicerRecipe.KEY_ITEM_CONTENTS, PersistentDataType.STRING);
        if (contentsStr == null || contentsStr.isEmpty()) {
            p.sendMessage("§c这个物品不能投入酒窖！");
            return;
        }
        if (st.hasAlcohol()) {
            p.sendMessage("§c酒窖中已含酒精液体，不能投入普通果汁！");
            return;
        }
        boolean bucket = pdc.has(JuicerRecipe.KEY_ITEM_BUCKET, PersistentDataType.BYTE);
        int add = bucket ? 3 : 1;
        if (!st.canAccept(add)) {
            p.sendMessage("§c酒窖液位容量不足！（容量 " + WineCellarState.CAPACITY + " 单位）");
            return;
        }
        Map<String, Integer> contents = JuicerRecipe.parseContents(contentsStr);
        int sugarPerUnit = pdc.getOrDefault(JuicerRecipe.KEY_ITEM_SUGAR, PersistentDataType.INTEGER, 0);
        String juicer = pdc.get(JuicerRecipe.KEY_ITEM_PLAYERS, PersistentDataType.STRING);
        consumeOne(p);
        st.addLiquid(contents, sugarPerUnit, add, juicer);
        give(p, new ItemStack(bucket ? Material.BUCKET : Material.GLASS_BOTTLE));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
    }

    private static void extract(Player p, Block manager) {
        WineCellarState st = WineCellarState.get(manager);
        if (st.phase() != WineCellarState.Phase.READY) {
            p.sendMessage(st.phase() == WineCellarState.Phase.RUNNING
                    ? "§c机器运行中，完成后才能出酒！" : "§c尚无可出酒的液体！");
            return;
        }
        if (st.units() <= 0) {
            p.sendMessage("§c酒窖已空！");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isVanilla(hand, Material.GLASS_BOTTLE)) return;
        consumeOne(p);
        int perBottleSugar = st.totalSugar() / Math.max(1, st.units());
        give(p, WineBottle.create(st.alcohol(), perBottleSugar,
                st.contentsOfAll(), st.allPlayers()));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        st.drainUnit();
        if (st.units() <= 0) {
            st.clear();
            p.sendMessage("§a酒窖已出酒完毕，已复原。");
        }
        st.save(manager);
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
                    // 陈化按「完整游戏日」计数：不满 1 个游戏日不计入
                    long days = st.elapsedMs() / WineCellarState.GAME_DAY_MS;
                    long into = st.elapsedMs() % WineCellarState.GAME_DAY_MS;
                    lore.add(ChatColor.GRAY + "已陈化: " + ChatColor.YELLOW + days + ChatColor.GRAY + " 游戏日");
                    lore.add(ChatColor.GRAY + "当日进度: " + ChatColor.YELLOW
                            + (into * 100 / WineCellarState.GAME_DAY_MS) + "%"
                            + ChatColor.DARK_GRAY + "（不满 1 游戏日不计入）");
                }
                lore.add(ChatColor.GRAY + "电力不足时计时暂停");
            } else {
                lore.add(ChatColor.GRAY + "当前未运行");
            }
            lore.add("");
            lore.add(st.mode() == WineCellarState.Mode.BREW
                    ? ChatColor.GRAY + "酿造: 糖分×比率+酒曲 → 酒精度，20~40 分钟"
                    : ChatColor.GRAY + "陈化: 每游戏日（24 分钟）酒精度按比例增长");
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
                    lore.add(ChatColor.GRAY + "液位: " + st.units() + "/" + WineCellarState.CAPACITY + "（需 ≥8）");
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
                    lore.add(ChatColor.GRAY + "正在耗电运行（断电暂停）");
                    if (st.mode() == WineCellarState.Mode.BREW) {
                        lore.add(ChatColor.RED + "酿造中关闭将报废全部液体！");
                    } else {
                        lore.add(ChatColor.GRAY + "关闭后停止计时，可出酒");
                    }
                }
                case READY -> {
                    meta.setDisplayName(ChatColor.GREEN + "已完成 · 添料区放玻璃瓶灌装");
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

    private static String contentsText(Map<String, Integer> contents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(legLegacy(JuicerRecipe.nameComponent(e.getKey())));
            if (e.getValue() > 1) sb.append("×").append(e.getValue());
        }
        return sb.toString();
    }

    private static String legLegacy(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    private static String fmt(double alcohol) {
        return String.format("%.1f", alcohol);
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + "分" + (sec % 60) + "秒";
    }

    private static void give(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
    }

    private static void consumeOne(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
    }

    private static boolean isVanilla(ItemStack item, Material m) {
        return item != null && item.getType() == m
                && io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item) == null;
    }
}
