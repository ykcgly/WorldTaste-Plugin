package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.items.ConsumableItem;
import com.haiman233.worldtaste.items.CropBlock;
import com.haiman233.worldtaste.load.WTConfig;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import java.util.List;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 种植与放置保护（统一走 {@link BlockPlaceEvent}，不依赖 Slimefun 内部放置流程）：
 * <ol>
 *   <li>所有作物种子（{@link CropBlock}）放置校验：显式 plantOn 或按材质推断的种植要求
 *       （耕地/灵魂沙/丛林原木/末地石等原版机制），不满足则取消放置并提示。
 *       方向性作物（COCOA 等需侧面附着的）必须以侧面附着方式放置且附着方块符合要求，
 *       地面式放置（如点在原木顶面）无效。</li>
 *   <li>食物（{@link ConsumableItem}）：食用与放置的分流已在 ConsumableItem 的右键处理器内判定——
 *       潜行右键可放置表面时插件<b>完全不干涉</b>，由服务端走原版放置（正确的落点/朝向/方块数据/
 *       替换判定/音效）。放置事件上另有两级处理（见 {@link #onPlaceUncancel} 与 {@link #onPlaceFinalize}）：
 *       实测服内多个附属插件会在 BlockPlaceEvent 上否决这类自定义头颅食物（权限/领地均无关），
 *       默认按 {@code food.ignore-place-veto} 强制放行，保证潜行右键必定能放置；登记粘液数据
 *       统一在 MONITOR 阶段按事件最终状态执行。</li>
 *   <li>其他 NotPlaceable 装饰：直接允许放置并登记（不限制潜行）。</li>
 * </ol>
 */
public final class PlantGuardListener implements Listener {

    public static final PlantGuardListener INSTANCE = new PlantGuardListener();

    private PlantGuardListener() {}

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null || item.getType().isAir()) {
            // 幽灵放置：手持物品已被消费（如手持 1 个食物食用后主手清空），客户端仍声明放置——
            // Bukkit 会按客户端声明放置一个无 NBT 的原版头颅；正常放置必然手持物品，直接取消
            e.setCancelled(true);
            return;
        }
        SlimefunItem sf = resolve(e, item);
        if (sf == null) return;

        // 作物种子：种植要求校验（全部种子类生效）
        if (sf instanceof CropBlock crop) {
            List<Material> allowed = crop.getPlantOn();
            if (allowed != null && !allowed.isEmpty()) {
                Block block = e.getBlock();
                boolean ok;
                if (crop.isDirectionalCrop()) {
                    // 方向性作物（如 COCOA，facing 须指向附着的原木）：必须以侧面附着方式放置
                    // 且附着方块符合 plantOn；地面式放置（如点在原木顶面）无法附着，直接拒绝，
                    // 否则种子头转换为作物方块后会因附着失效被原版反复破坏
                    ok = block.getBlockData() instanceof Directional dir
                            && allowed.contains(block.getRelative(dir.getFacing().getOppositeFace()).getType());
                } else {
                    // 地面作物：仅要求脚下方块符合 plantOn（点在支撑侧面放置的悬空种子无效）
                    ok = allowed.contains(block.getRelative(BlockFace.DOWN).getType());
                }
                if (!ok) {
                    e.setCancelled(true);
                    Player p = e.getPlayer();
                    if (p != null) p.sendMessage("该作物只能种植在 " + names(allowed) + " 上！");
                }
            }
            return;
        }

        // 其他不可放置装饰（非食物）：允许放置并登记
        if (sf instanceof NotPlaceable && !(sf instanceof ConsumableItem)) {
            BlockStorage.store(e.getBlock(), sf.getId());
        }
        // 食物的登记统一在 onPlaceFinalize（MONITOR，按事件最终状态执行）
    }

    /**
     * 强制放行（HIGHEST，先于 MONITOR 终态判定），带探针判定：
     * 实测服内某个附属插件会在 {@code BlockPlaceEvent} 上专项否决尘世百味的自定义头颅食物
     * （症状：潜行右键永远放不出来，与权限/领地无关）。按 {@code config.yml: food.ignore-place-veto}
     * （默认 true）放行这类否决，放置继续走原版管线（手感不变）。
     *
     * <p>为避免误伤领地等保护插件，放行前先发一个「探针」合成事件：用普通圆石在<b>同一位置</b>
     * 走一遍放置判定——普通方块同样被否决 = 位置类保护（领地/权限），<b>尊重否决</b>；
     * 普通方块能放而唯独食物被拦 = 专项物品否决，<b>强制放行</b>。仅限食物物品，
     * 不影响其它方块的正常保护。若否决发生在 MONITOR 阶段（极少数），本方法无法覆盖，
     * 终态日志会如实记录。注意：探针事件会让 CoreProtect 等记录类插件多记一条从未落地的圆石放置。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlaceUncancel(BlockPlaceEvent e) {
        if (!WTConfig.foodIgnorePlaceVeto || !e.isCancelled()) return;
        ItemStack item = e.getItemInHand();
        if (item == null || item.getType().isAir()) return;
        SlimefunItem sf = resolve(e, item);
        if (!(sf instanceof ConsumableItem)) return;

        // 探针判定：同位置、同玩家的普通圆石放置是否也被否决——
        // 被否决 = 位置类保护（领地/权限），尊重；未被否决 = 专项物品否决，放行
        org.bukkit.block.BlockState replaced = e.getBlock().getState();
        BlockPlaceEvent canary = new BlockPlaceEvent(e.getBlock(), replaced, e.getBlockAgainst(),
                new ItemStack(Material.STONE), e.getPlayer(), true, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(canary);
        if (canary.isCancelled()) {
            if (WTConfig.debugFood) {
                WT.log("[debug-food] " + sf.getId() + " 同位置普通方块也被否决 → 领地/保护类限制，尊重否决");
            }
            return;
        }

        e.setCancelled(false);
        if (WTConfig.debugFood) {
            WT.log("[debug-food] " + sf.getId() + " 放置曾被外部插件否决（普通方块可放）→ 已强制放行");
        }
    }

    /**
     * 终态处理（MONITOR）：食物的粘液数据登记统一在此按事件最终状态执行——
     * 无论中途是否被否决过、也无论否决者处于哪个优先级，只有真正会落地的放置才登记，
     * 避免「事件又被更高优先级取消却已登记」的幽灵数据。
     * 被取消且未被放行时，debug 模式下列出监听本事件的候选插件。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlaceFinalize(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null || item.getType().isAir()) return;
        SlimefunItem sf = resolve(e, item);
        if (sf instanceof CropBlock) {
            // 作物：终态确认放置未被取消后才登记（被取消的位置登记会残留，
            // 进而对空位置跑一次完整 tick：材质不符 → purge + clearBlockInfo）
            if (!e.isCancelled()) CropBlock.markPlaced(e.getBlock());
            return;
        }
        if (!(sf instanceof ConsumableItem)) return;

        if (e.isCancelled()) {
            if (WTConfig.debugFood) {
                StringBuilder cand = new StringBuilder();
                for (org.bukkit.plugin.RegisteredListener rl : e.getHandlers().getRegisteredListeners()) {
                    String name = rl.getPlugin().getName();
                    if (name.equals("WorldTaste") || name.equals("Slimefun")) continue;
                    if (cand.length() > 0) cand.append(", ");
                    cand.append(name);
                }
                WT.log("[debug-food] " + sf.getId() + " 放置最终被取消（含 MONITOR 阶段否决，"
                        + "food.ignore-place-veto 未覆盖）；候选插件: "
                        + (cand.length() == 0 ? "（无）" : cand));
            }
            return;
        }
        // 原版已按自己的逻辑摆放成功：登记 Slimefun 方块数据，
        // 挖掘/爆炸/活塞/水流破坏时掉落带数据的物品
        BlockStorage.store(e.getBlock(), sf.getId());
    }

    // 同一次放置会被本类的三个 handler（HIGH/HIGHEST/MONITOR）各解析一次物品；
    // 事件分发是同步串行的，用单条目 memo 按事件身份缓存结果，一次放置只解析一次
    private static BlockPlaceEvent memoEvent;
    private static SlimefunItem memoItem;

    private static SlimefunItem resolve(BlockPlaceEvent e, ItemStack item) {
        if (memoEvent == e) return memoItem;
        memoItem = resolve(item);
        memoEvent = e;
        return memoItem;
    }

    /** 从物品反查注册的 Slimefun 物品（先按 PDC id，再按外观兜底）。 */
    private static SlimefunItem resolve(ItemStack item) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        if (sf == null) {
            // 兜底：无 id 展示物品（如未注册物品的机器/多方块产物）按外观反查注册物品
            sf = com.haiman233.worldtaste.util.Stacks.findRegisteredByAppearance(item);
        }
        return sf;
    }

    private static String names(List<Material> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(com.haiman233.worldtaste.util.ZhNames.orEnglish(list.get(i)));
        }
        return sb.toString();
    }
}
