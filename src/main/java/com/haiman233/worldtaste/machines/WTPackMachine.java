package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Lang;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 打包台（pack_machines.yml，WT_DABAOTAI）：头颅类食物的打包机（食用形态 → 放置形态）。
 * 不耗电、无进度、不参与粘液 tick（1.10.3 起，海曼书皮压缩机模式：
 * extends {@link SlimefunItem} 而非 AContainer，从根源脱离电力组件与 tick 调度）。
 *
 * <p>双形态（同一 Slimefun id，PDC id 不变，指南/配方/机器识别全部兼容）：</p>
 * <ul>
 *   <li><b>食用形态</b>：1.21.4+ 为下界砖本体 + item_model 头颅渲染（右键直接原版进食，
 *       无放置争抢）；低版本为头颅本体 + 进食组件；</li>
 *   <li><b>放置形态</b>：真头颅（{@code WT.preload} 的原头颅模板），可摆放装饰、
 *       不含进食组件，PDC {@code pack_form=1} 打标，lore 末尾追加「已打包」提示。</li>
 * </ul>
 *
 * <p>交互：槽 10 放待打包食物、槽 13 放打包袋（WT_DABAODAI，每次消耗 1 个）、
 * 点击槽 22「点击打包」→ 输入槽<b>整堆</b>转换为放置形态进槽 16。机器只负责打包
 * （食用形态 → 放置形态）；拆包（放置形态 → 食用形态）改由手持已打包物品
 * <b>蹲下右键空气</b>完成（{@link UnpackListener}），不返还打包袋。</p>
 *
 * <p>形态判别用 PDC 而非材质/组件：低版本（1.20.4-，无组件 API）下两种形态都是头颅材质，
 * 只有 PDC 标记可跨版本区分——食用形态无标记（默认），放置形态打标。</p>
 */
public class WTPackMachine extends SlimefunItem implements InventoryBlock {

    /** 放置形态模板（头颅原形态）：加载期从 WT.preload 固化——loadAll 末尾 preload 会被清空（R9）。 */
    private static final Map<String, ItemStack> PLACEABLE_TEMPLATES = new ConcurrentHashMap<>();

    /** 支持转换的食物 id 集合（加载期固化，与模板表同键）。 */
    private static final Set<String> SUPPORTED = new HashSet<>();

    private final int convertSlot;
    private final int bagSlot;
    private final int outputSlot;
    private final int clickSlot;
    private final String bagId;
    private final MenuDef menu;

    /** 加载期固化放置形态模板（Setup.loadAll 内 freezePlaceableTemplates 调用，须在 WT.preload.clear() 之前）。 */
    public static void putPlaceableTemplate(String sfId, ItemStack headTemplate) {
        PLACEABLE_TEMPLATES.put(sfId, headTemplate);
        SUPPORTED.add(sfId);
    }

    /**
     * 全量固化放置形态模板（Setup.loadAll 内、所有内容注册完毕且 {@code WT.preload.clear()}（R9）
     * 之前调用一次）：扫描预加载展示堆，凡<b>有头颅模型（展示堆为头颅材质）+ 带有 food 组件</b>
     * 的已注册物品均可打包——food 判定：脚本消耗品（{@link ConsumableItem} 及其子类）直接认定；
     * 其余（foods.yml 无脚本食物等）按注册本体是否已注入 consumable 组件判定。
     * 未注册（register.conditions 禁用/加载失败）或非食物的头颅（装饰头颅等）一律排除。
     * 返回固化数量（日志汇总用）。
     */
    public static int freezePlaceableTemplates() {
        int n = 0;
        for (Map.Entry<String, ItemStack> en : WT.preload.entrySet()) {
            ItemStack display = en.getValue();
            if (display == null || !com.haiman233.worldtaste.load.FoodHelper.isHeadMaterial(display.getType())) continue;
            SlimefunItem sf = SlimefunItem.getById(en.getKey());
            if (sf == null) continue;
            // hasConsumable 在 legacy/noop 版本段恒 true（无组件 API 可查）——低版本下无脚本的
            // 装饰头颅会误入集合；脚本消耗品判定在先可覆盖绝大多数场景，高版本（1.20.5+）判定精确
            if (!(sf instanceof com.haiman233.worldtaste.items.ConsumableItem)
                    && !com.haiman233.worldtaste.load.FoodHelper.hasConsumable(sf.getItem())) continue;
            putPlaceableTemplate(sf.getId(), display.clone());
            n++;
        }
        return n;
    }

    public WTPackMachine(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe,
                         int convertSlot, int bagSlot, int outputSlot, int clickSlot,
                         String bagId, MenuDef menu) {
        super(group, item, rt, recipe);
        this.convertSlot = convertSlot;
        this.bagSlot = bagSlot;
        this.outputSlot = outputSlot;
        this.clickSlot = clickSlot;
        this.bagId = bagId == null ? "WT_DABAODAI" : bagId.toUpperCase(Locale.ROOT);
        this.menu = menu;
        addItemHandler(new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = BlockStorage.getInventory(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), convertSlot, bagSlot, outputSlot);
                }
            }
        });
        createPreset();
    }

    private void constructMenu(BlockMenuPreset preset) {
        Set<Integer> placed = new HashSet<>();
        // 装饰（来自 menus.yml：橙/绿/黄玻璃 + 蓝色「点击打包」按钮外观）
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> e : menu.items.entrySet()) {
                preset.addItem(e.getKey(), e.getValue(), ChestMenuUtils.getEmptyClickHandler());
                placed.add(e.getKey());
            }
        }
        int declared = (menu != null && menu.size > 0) ? menu.size : 27;
        int maxSlot = declared - 1;
        maxSlot = Math.max(maxSlot, convertSlot);
        maxSlot = Math.max(maxSlot, bagSlot);
        maxSlot = Math.max(maxSlot, outputSlot);
        maxSlot = Math.max(maxSlot, clickSlot);
        int size = Math.min(54, Math.max(declared, ((maxSlot / 9) + 1) * 9));
        // 背景：功能槽与装饰槽之外全部填充
        for (int i = 0; i < size; i++) {
            if (i == convertSlot || i == bagSlot || i == outputSlot || placed.contains(i)) continue;
            preset.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        // 输出槽：允许取出（左键提起 / shift 批量取出）、禁止放入（对齐 MachineMenuBuilder 语义）
        preset.addMenuClickHandler(outputSlot, new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(org.bukkit.event.inventory.InventoryClickEvent e, Player player,
                                   int slot, ItemStack cursor, me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
            }

            @Override
            public boolean onClick(Player player, int slot, ItemStack cursor,
                                   me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction action) {
                return action.isShiftClicked() || cursor == null || cursor.getType() == Material.AIR;
            }
        });
    }

    private BlockMenuPreset createPreset() {
        return new BlockMenuPreset(getId(), getItemName()) {
            @Override
            public void init() {
                constructMenu(this);
            }

            @Override
            public void newInstance(BlockMenu bm, Block b) {
                bm.addMenuClickHandler(clickSlot, (player, slot, clicked, action) -> {
                    pack(bm, player);
                    return false;
                });
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                // cargo：INSERT 允许向转换槽/袋槽投放，WITHDRAW 从输出槽抽取
                return flow == ItemTransportFlow.INSERT ? new int[]{convertSlot, bagSlot} : new int[]{outputSlot};
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                if (p.hasPermission("slimefun.inventory.bypass")) return true;
                return WTPackMachine.this.canUse(p, false)
                        && (io.github.thebusybiscuit.slimefun4.implementation.Slimefun.instance().isUnitTest()
                        || io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getProtectionManager()
                                .hasPermission(p, b.getLocation(),
                                        io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction.INTERACT_BLOCK));
            }
        };
    }

    @Override
    public int[] getInputSlots() { return new int[]{convertSlot, bagSlot}; }

    @Override
    public int[] getOutputSlots() { return new int[]{outputSlot}; }

    /** 打/清形态标记（原地修改传入堆）。 */
    private static void applyForm(ItemStack item, boolean placeable) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || WT.packFormKey == null) return;
        if (placeable) {
            meta.getPersistentDataContainer().set(WT.packFormKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            meta.getPersistentDataContainer().remove(WT.packFormKey);
        }
        item.setItemMeta(meta);
    }

    /** 给普通堆写入 Slimefun 物品 id（PDC slimefun_item），使两形态同 id 可被 getByItem 识别。 */
    private static void applySfId(ItemStack item, String id) {
        try {
            io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getItemDataService().setItemData(item, id);
        } catch (Throwable t) {
            WT.log("WTPackMachine applySfId " + id + ": " + t);
        }
    }

    /** 打包产物 lore 末尾追加「已打包」提示（&7 灰色，键 msg.packmachine.packed）。 */
    private static void applyPackedLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        java.util.List<String> lore = meta.getLore() == null
                ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.getLore());
        lore.add(com.haiman233.worldtaste.util.Colors.c(Lang.get("msg.packmachine.packed")));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    /** 点击「打包」：校验 → 消耗 1 袋 → 输入槽整堆转换为放置形态进输出槽。 */
    private void pack(BlockMenu menu, Player p) {
        ItemStack input = menu.getItemInSlot(convertSlot);
        if (input == null || input.getType().isAir()) {
            p.sendMessage(Lang.get("msg.packmachine.no-input"));
            return;
        }
        SlimefunItem sf = SlimefunItem.getByItem(input);
        if (sf == null || !SUPPORTED.contains(sf.getId())) {
            p.sendMessage(Lang.get("msg.packmachine.002"));
            return;
        }
        // 已是放置形态：机器只打包不拆包（拆包走手持蹲下右键空气），拒绝重复打包
        if (com.haiman233.worldtaste.util.Stacks.isPlaceableForm(input)) {
            p.sendMessage(Lang.get("msg.packmachine.007"));
            return;
        }
        ItemStack out = menu.getItemInSlot(outputSlot);
        if (out != null && !out.getType().isAir()) {
            p.sendMessage(Lang.get("msg.packmachine.003"));
            return;
        }
        ItemStack bag = menu.getItemInSlot(bagSlot);
        if (bag == null || bag.getType().isAir()) {
            p.sendMessage(Lang.get("msg.packmachine.004"));
            return;
        }
        SlimefunItem bagSf = SlimefunItem.getByItem(bag);
        if (bagSf == null || !bagSf.getId().equals(bagId)) {
            p.sendMessage(Lang.get("msg.packmachine.005"));
            return;
        }
        // 放置形态模板 = 加载期固化的原头颅（旧存档头颅物品无 pack_form 标记同样视为
        // 食用形态，可正常打包——按 PDC 判别天然兼容新旧物品混存）
        ItemStack template = PLACEABLE_TEMPLATES.get(sf.getId());
        if (template == null) {
            p.sendMessage(Lang.get("msg.packmachine.006"));
            return;
        }
        ItemStack target = template.clone();
        target.setAmount(input.getAmount());
        // 放置形态模板是 preload 原始展示堆（无 sf id PDC）——不补写 id 的话打包产物无法被
        // SlimefunItem.getByItem 识别（拆包监听与再次打包全部失灵），故按「两形态同一 id」
        // 约定写入 Slimefun 物品 id 数据服务
        applySfId(target, sf.getId());
        applyForm(target, true);
        applyPackedLore(target);
        menu.replaceExistingItem(outputSlot, target);
        menu.consumeItem(bagSlot, 1);
        menu.replaceExistingItem(convertSlot, null);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1f, 1.2f);
    }

    /** 注册拆包监听（PackMachineLoader.load() 末尾调用，仅注册一次）。 */
    public static void register() {
        Bukkit.getPluginManager().registerEvents(new UnpackListener(), WT.plugin);
    }

    /**
     * 拆包监听：手持放置形态（pack_form=1）的受支持头颅食物<b>蹲下右键空气</b> →
     * 转回食用形态（注册物品模板），不消耗打包袋。仅监听 RIGHT_CLICK_AIR——
     * 蹲下右键方块仍保留原版放置头颅的语义，不与放置争抢。
     */
    private static final class UnpackListener implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void onInteract(PlayerInteractEvent e) {
            if (e.getHand() != EquipmentSlot.HAND) return;
            if (e.getAction() != Action.RIGHT_CLICK_AIR) return;
            ItemStack item = e.getItem();
            if (item == null || item.getType().isAir()) return;
            if (!com.haiman233.worldtaste.util.Stacks.isPlaceableForm(item)) return;
            SlimefunItem sf = SlimefunItem.getByItem(item);
            if (sf == null || !SUPPORTED.contains(sf.getId())) return;
            Player p = e.getPlayer();
            if (!p.isSneaking()) return;
            // 转回食用形态（模板无 pack_form 标记，applyForm(false) 再保险清一次）。
            // 上面已限定 hand == HAND，直接写主手槽位同步回客户端
            ItemStack target = sf.getItem().clone();
            target.setAmount(item.getAmount());
            applyForm(target, false);
            p.getInventory().setItemInMainHand(target);
            e.setCancelled(true);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1f, 0.8f);
        }
    }
}
