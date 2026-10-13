package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.behavior.Behaviors.Potion;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
import com.haiman233.worldtaste.load.FoodHelper;
import com.haiman233.worldtaste.load.WTConfig;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 消耗型食物（右键食用）。覆盖原 WT_eatConsumable 与独立食物脚本(yl/tang/jiu/yan/zhongdu 等)：
 * 主手消耗、副手校验(默认禁粘液物品；offhandTool 指定必备工具如 yan 打火石/xuejia 剪刀)、按 opts 恢复饥饿/饱和/消耗/空气/冻结并施加药水。
 *
 * <p>右键语义（恢复「右键=吃 / 潜行=放」，放置交给原版管线，插件不重实现）：
 * <ul>
 *   <li>右键（非潜行，无论指向哪里） = <b>食用</b>；潜行右键 + 方块材质（头颅类等）+ 存在可放置
 *       落点 = <b>放置</b>（零干预交还原版管线）；潜行但无可放置落点（对着空气），或材质没有
 *       方块形态（饮品 POTION、COOKED_MUTTON 肉类等）= <b>食用</b>。</li>
 *   <li>食用的实现由 {@code food.native-eat} 决定：
 *       <b>true</b>（默认，服务端 ≥1.20.5）= 原版进食管线——物品在加载期注入 FoodComponent +
 *       consumable 组件（动画/咀嚼音/时长原生，饮品走 DRINK 动画与饮用音效），本处理器只做
 *       「放置/进食」分流（{@link #nativeEat}）：放行物品使用，仅当原版真会去放置方块时才
 *       DENY 方块使用；脚本效果由 FoodConsumeListener 在 PlayerItemConsumeEvent 补足。
 *       <b>false</b> 或低版本 = 插件结算（{@link #eat}）：DENY 原版使用物品后立即扣除并加成，
 *       无进食动画。</li>
 *   <li>offhandTool 使用型物品（吸烟需打火石、雪茄需剪刀）永不放置、永不走原版进食
 *       （不注入可食组件），保持插件使用逻辑。</li>
 * </ul>
 */
public class ConsumableItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {

    private final ConsumableOpts opts;

    public ConsumableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, ConsumableOpts opts) {
        super(group, item, rt, recipe);
        this.opts = opts;
    }

    /** 本食物的消耗参数（供 {@link com.haiman233.worldtaste.behavior.FoodConsumeListener} 在原版进食后补效果）。 */
    public ConsumableOpts opts() {
        return opts;
    }

    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            // Slimefun 对主手与副手都会派发且不区分：哪只手拿着本食物就按哪只手处理
            // （副手右键放食物 = 原版把副手方块物品直接摆出去，必须同样接管）。
            // 双手都拿食物时主手优先：副手分发直接跳过，避免一次点击吃两份。
            EquipmentSlot hand = e.getHand();
            Player p = e.getPlayer();
            ItemStack mainHeld = p.getInventory().getItemInMainHand();
            if (hand == EquipmentSlot.OFF_HAND && mainHeld != null && !mainHeld.getType().isAir()
                    && SlimefunItem.getByItem(mainHeld) instanceof ConsumableItem) {
                dbg(p, "主手也持有食物 → 副手分发跳过（主手优先，防双份食用）");
                return;
            }
            ItemStack held = e.getItem();
            Material m = held == null ? Material.AIR : held.getType();

            // 原版进食模式（food.native-eat，服务端 ≥1.20.5）：食物类（非 offhandTool 使用工具）
            // 走「分流不代吃」——进食动画/咀嚼音/时长由原版管线承担，脚本效果在
            // PlayerItemConsumeEvent 里补足（FoodConsumeListener）
            if (WTConfig.nativeEat && FoodHelper.hasFoodApi() && opts.offhandTool == null) {
                nativeEat(e, p, m);
                return;
            }

            PlayerInventory inv = p.getInventory();

            // 潜行 + 方块材质 + 无需副手工具 + 存在可放置落点 = 放置意图：零干预交还原版
            // （不对事件做任何修改，Slimefun 把原样的 useItem/useBlock 写回，服务端走自己的放置流程，
            // 从触发的那只手扣物品；BlockPlaceEvent 里的登记由 PlantGuardListener 负责）。
            // 例外：offhandTool 使用型物品（WT_YAN 打火石/WT_XUEJIA 剪刀）永不放置。
            if (p.isSneaking() && opts.offhandTool == null) {
                String target = placementTargetDesc(e, m);
                if (target != null) {
                    dbg(p, (hand == EquipmentSlot.HAND ? "主手" : "副手") + "潜行+可放置落点("
                            + m + " → " + target + ") → 零干预，交还原版放置");
                    return;
                }
                dbg(p, "潜行但无可放置落点(材质=" + m + ") → 改走食用");
            } else if (p.isSneaking()) {
                dbg(p, "潜行+offhandTool 使用型物品(" + m + ") → 永不放置，走食用");
            }

            // 食用：DENY 原版「使用物品」（原版放置 + 原版白吃——1.21.2+ 的 consumable 组件
            // 可能在饥饿时抢走本次交互），自行执行食用逻辑。
            // 只 DENY useItem、不动 useBlock，以免手持食物时无法右键箱子/门等方块。
            if (opts.requireHungry && p.getFoodLevel() >= 20) {
                // 饱食度已满且该食物要求「饿了才吃」：什么都不做，也绝不允许被消耗。
                // 实测仅有 setUseItem(DENY) 时仍会被第三方路径消耗（食物的 consumable 组件
                // /其它插件），故这里把底层 PlayerInteractEvent 整个取消——useItem 与
                // useBlock 一并 DENY，服务端与插件侧都不会再有后续动作。
                dbg(p, "饱食度已满且要求饥饿 → 不食用、不消耗（并取消底层交互）");
                e.setUseItem(Event.Result.DENY);
                e.getInteractEvent().setCancelled(true);
                return;
            }
            dbg(p, "→ DENY 原版使用物品，自行食用");
            e.setUseItem(Event.Result.DENY);
            eat(p, hand);
        };
    }

    /**
     * 原版进食分流（{@code food.native-eat=true} 且服务端支持组件 API）：本方法<b>不代吃</b>，
     * 只决定本次交互交给谁——
     * <ul>
     *   <li>潜行 + 可放置落点 → 零干预（原版放置优先，与旧语义一致）；</li>
     *   <li>要求饥饿且饱食度已满 → 全 DENY（原版本也不会开始进食，这里兜底防第三方消耗）；</li>
     *   <li>另一只手持有粘液物品 → 拒绝（沿用旧副手校验语义）；</li>
     *   <li>其余 → 放行物品使用（原版 consumable 组件开始进食动画）。
     *       仅当「原版这次真的会去放置方块」（非潜行 + 方块材质 + 落点可放置 + 所指方块
     *       无交互行为）时才 DENY 方块使用，把交互转进 useItem（进食）路径；
     *       其余情况保留方块标志——手持食物右键箱子/门仍可正常打开（与原版食物一致），
     *       对无交互的普通方块原版会自动 fall-through 到物品使用。</li>
     * </ul>
     */
    private void nativeEat(PlayerRightClickEvent e, Player p, Material m) {
        // 另一只手校验（与旧 eat() 语义一致；offhandTool 工具不进本分支）
        PlayerInventory inv = p.getInventory();
        boolean mainHand = e.getHand() == EquipmentSlot.HAND;
        ItemStack other = mainHand ? inv.getItemInOffHand() : inv.getItemInMainHand();
        if (other != null && SlimefunItem.getByItem(other) != null) {
            dbg(p, "另一手持有粘液物品 → 拒绝食用");
            p.sendMessage("您必须空出另一只手进食且" + (mainHand ? "副手" : "主手") + "不能持有粘液科技物品！");
            e.setUseItem(Event.Result.DENY);
            return;
        }
        // 潜行 + 可放置落点 = 放置意图：零干预交还原版放置
        if (p.isSneaking() && m.isBlock() && !m.isAir()) {
            String target = placementTargetDesc(e, m);
            if (target != null) {
                dbg(p, (mainHand ? "主手" : "副手") + "潜行+可放置落点(" + m + " → " + target
                        + ") → 零干预，交还原版放置");
                return;
            }
        }
        if (opts.requireHungry && p.getFoodLevel() >= 20) {
            // 原版本不会开始进食（canAlwaysEat=false）；全 DENY 兜底防第三方消耗路径
            dbg(p, "饱食度已满且要求饥饿 → 不食用、不消耗（并取消底层交互）");
            e.setUseItem(Event.Result.DENY);
            e.getInteractEvent().setCancelled(true);
            return;
        }
        // 放行物品使用 → 原版 consumable 组件开始进食（动画/音效/时长原生）
        e.setUseItem(Event.Result.ALLOW);
        if (!p.isSneaking() && m.isBlock() && !m.isAir() && placementTargetDesc(e, m) != null) {
            Block clicked = e.getClickedBlock().orElse(null);
            if (clicked != null && !clicked.getType().isInteractable()) {
                // 原版会优先走放置：DENY 方块阻止实际放置（客户端预测只闪一下，属客户端行为）。
                // 注意 CraftBukkit 在 DENY 方块后会提前返回、不继续 useItem 路径——
                // 故需主动模拟一次「对空使用」让服务端进入原版进食（ServerInteract 特征探测）
                e.setUseBlock(Event.Result.DENY);
                boolean forced = com.haiman233.worldtaste.util.ServerInteract.forceAirUse(p, e.getHand());
                dbg(p, "落点可放置(" + m + ") → DENY 方块 + " + (forced ? "模拟对空使用" : "（模拟失败）")
                        + " → 原版进食");
                return;
            }
        }
        dbg(p, "→ 放行物品使用，交原版进食管线");
    }

    /**
     * 本次右键是否存在「服务端会去放置方块」的落点。
     *
     * <p>判定沿用服务端自己的语义：物品材质必须是可放置方块，且准星所指方块面朝向的位置
     * 是可替换的（{@link Block#isEmpty()} 空气 / {@link Block#isReplaceable()} 草丛·雪层一类
     * / {@link Block#isLiquid()} 水面）。仅用于决策「是否把本次交互交给原版放置」——
     * 误判最多变成「潜行时改走食用」，绝不自写放置。
     *
     * @return 落点描述（如 {@code AIR @ UP}）便于 debug 日志判读；无落点返回 null。
     */
    private static String placementTargetDesc(PlayerRightClickEvent e, Material m) {
        if (!m.isBlock() || m.isAir()) return null;
        Block clicked = e.getClickedBlock().orElse(null);
        BlockFace face = e.getClickedFace();
        if (clicked == null || face == null) return null;
        Block target = clicked.getRelative(face);
        boolean ok = target.isEmpty() || target.isReplaceable() || target.isLiquid();
        return ok ? (target.getType() + " @" + face) : null;
    }

    /** 调试日志（config.yml 的 debug.food 开启时输出）。 */
    private void dbg(Player p, String msg) {
        if (WTConfig.debugFood) WT.log("[debug-food] " + getId() + " (" + p.getName() + "): " + msg);
    }

    /** 右键食用（主手或副手均可触发，消耗与校验都作用于触发的那只手）。 */
    private void eat(Player p, EquipmentSlot hand) {
        if (opts.requireHungry && p.getFoodLevel() >= 20) return;
        PlayerInventory inv = p.getInventory();
        boolean mainHand = hand == EquipmentSlot.HAND;
        ItemStack held = mainHand ? inv.getItemInMainHand() : inv.getItemInOffHand();
        ItemStack other = mainHand ? inv.getItemInOffHand() : inv.getItemInMainHand();

        if (opts.offhandTool != null) {
            // 配套工具在「另一只手」（主手食物→副手工具；副手食物→主手工具）
            if (other == null || other.getType() != opts.offhandTool) {
                p.sendMessage("您必须使用" + (mainHand ? "主手" : "副手") + "且另一只手持有 "
                        + com.haiman233.worldtaste.util.ZhNames.orEnglish(opts.offhandTool) + "！");
                return;
            }
        } else if (other != null && SlimefunItem.getByItem(other) != null) {
            p.sendMessage("您必须空出另一只手进食且" + (mainHand ? "副手" : "主手") + "不能持有粘液科技物品！");
            return;
        }

        if (held == null || held.getAmount() <= 0) return;
        // 到 0 必须清空槽位，避免 0 数量幽灵物品残留（否则下次右键仍被识别/显示）
        if (mainHand) Stacks.consumeOneInMainHand(inv);
        else Stacks.consumeOneInOffHand(inv);
        if (opts.offhandTool != null && opts.consumeOffhand) {
            // 工具类另一手（打火石/剪刀等有耐久物品）按 1 点耐久损耗，与原版使用工具一致；
            // 原 yan.js/xuejia.js 的 setAmount-1 整件消耗属移植保真遗留（玩家反馈不合理）。
            // 不可损耗工具（无耐久上限）回退整件消耗；无敌工具不损耗也不消耗；
            // 耐久耗尽由 Stacks 清空槽位，防止 0 数量幽灵物品被 getType() 校验无限复用。
            if (mainHand) {
                if (!Stacks.damageToolInOffHand(inv, 1)) {
                    Stacks.consumeOneInOffHand(inv);
                }
            } else {
                if (!Stacks.damageToolInMainHand(inv, 1)) {
                    Stacks.consumeOneInMainHand(inv);
                }
            }
        }

        applyConsumeEffects(p, opts, getId(), false);

        // 音效：silent=不播；自定义 sound（喝汤播喝音/香烟打火石音等）；默认吃音效
        if (!opts.silent) {
            p.getWorld().playSound(p.getLocation(),
                    opts.sound != null ? opts.sound : Sound.ENTITY_STRIDER_EAT, 1f, 1f);
        }
    }

    /**
     * 应用消耗效果：饥饿/饱和/消耗/空气/冻结/游戏模式/药水/异域花园联动/提示。
     * 由旧路径 {@link #eat}（消耗物品后）与原版进食路径
     * （{@link com.haiman233.worldtaste.behavior.FoodConsumeListener}，物品已由原版消耗）共用。
     * 不含音效：旧路径由调用方按配置播放；原版路径由 consumable 组件动画音效承担。
     *
     * @param skipVanillaRestored true = 饥饿/饱和已由 FoodComponent 在原版进食时恢复
     *                            （简单数值型食物），跳过这两项防止双重恢复
     */
    public static void applyConsumeEffects(Player p, ConsumableOpts opts, String itemId, boolean skipVanillaRestored) {
        if (!skipVanillaRestored) {
            int food = opts.randomFood != null ? (ThreadLocalRandom.current().nextInt(opts.randomFood) + 1)
                    : (opts.food != null ? opts.food.intValue() : 0);
            if (opts.foodSet != null) p.setFoodLevel(opts.foodSet);
            else if (food > 0) p.setFoodLevel(p.getFoodLevel() + food);
            if (opts.saturationSet != null) p.setSaturation(opts.saturationSet);
            else if (opts.saturation != null) p.setSaturation((float) (p.getSaturation() + opts.saturation));
        }
        if (opts.exhaustion != null) p.setExhaustion((float) (p.getExhaustion() - opts.exhaustion));
        if (opts.exhaustionSet != null) p.setExhaustion(opts.exhaustionSet.floatValue());
        if (opts.absorption != null) p.setAbsorptionAmount(opts.absorption);
        if (opts.remainingAirAdd != null) p.setRemainingAir(p.getRemainingAir() + opts.remainingAirAdd);
        if (opts.gameMode != null) {
            try { p.setGameMode(org.bukkit.GameMode.valueOf(opts.gameMode.toUpperCase(java.util.Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        if (opts.satRegen != null) p.setSaturatedRegenRate(opts.satRegen);
        if (opts.unsatRegen != null) p.setUnsaturatedRegenRate(opts.unsatRegen);
        if (opts.starvation != null) p.setStarvationRate(opts.starvation);
        if (opts.maxAir != null) p.setMaximumAir(opts.maxAir);
        if (opts.remainingAir != null) p.setRemainingAir(opts.remainingAir);
        if (opts.freezeTicks != null) p.setFreezeTicks(opts.freezeTicks);

        for (Potion pt : opts.potions) {
            PotionEffectType type = PotionEffectType.getByName(pt.type);
            if (type != null) p.addPotionEffect(new PotionEffect(type, pt.duration, pt.amplifier, false));
            else WT.log("未知药水类型: " + pt.type);
        }

        // 异域花园联动：酒类饮品（items.yml alcohol 字段）饮用后累加其酒精度
        ExoticGardenHook.onDrink(p, itemId);

        if (opts.message != null) p.sendMessage(opts.message);
    }
}
