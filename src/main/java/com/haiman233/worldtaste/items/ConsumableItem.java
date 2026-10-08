package com.haiman233.worldtaste.items;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.behavior.Behaviors.ConsumableOpts;
import com.haiman233.worldtaste.behavior.Behaviors.Potion;
import com.haiman233.worldtaste.hook.ExoticGardenHook;
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
 *   <li>右键（非潜行，无论指向哪里） = <b>食用</b>：DENY 原版「使用物品」（阻止原版放置与
 *       原版白吃）后自行执行食用逻辑；饱食度已满且要求饥饿时同样 DENY 但<b>不消耗</b>。</li>
 *   <li>潜行右键 + 方块材质（头颅类等）+ 存在可放置落点 = <b>放置</b>：本处理器<b>什么都不做</b>，
 *       落点、朝向、方块数据（含头颅贴图）、替换判定（草丛/水/雪层等）、放置音效、数量扣减
 *       全部由服务端自己的逻辑完成，手感与原版完全一致；
 *       粘液数据在 {@link com.haiman233.worldtaste.behavior.PlantGuardListener} 的
 *       {@code BlockPlaceEvent} 里登记，挖掘时仍掉落带数据的物品。
 *       教训：曾在这里自己用 {@code setType} 摆方块，结果漏了头颅贴图（变默认玩家头）、
 *       可替换判定错（顶层雪上悬空）、放置音重复——绝不再自写放置。</li>
 *   <li>潜行右键但无可放置落点（对着空气），或材质没有方块形态（饮品 POTION、COOKED_MUTTON
 *       肉类等）= <b>食用</b>（不存在"放置"这件事，潜行不应造成白消耗）。<br>
 *       例外：配置了 {@code offhandTool} 的使用型物品（吸烟需打火石、雪茄需剪刀）永不放置——
 *       它们存在就是为了「用」，摆成方块没有意义。</li>
 * </ul>
 */
public class ConsumableItem extends SimpleSlimefunItem<ItemUseHandler> implements NotPlaceable {

    private final ConsumableOpts opts;

    public ConsumableItem(ItemGroup group, SlimefunItemStack item, RecipeType rt, ItemStack[] recipe, ConsumableOpts opts) {
        super(group, item, rt, recipe);
        this.opts = opts;
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
            PlayerInventory inv = p.getInventory();
            ItemStack held = e.getItem();
            Material m = held == null ? Material.AIR : held.getType();

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

        int food = opts.randomFood != null ? (ThreadLocalRandom.current().nextInt(opts.randomFood) + 1)
                : (opts.food != null ? opts.food.intValue() : 0);
        if (opts.foodSet != null) p.setFoodLevel(opts.foodSet);
        else if (food > 0) p.setFoodLevel(p.getFoodLevel() + food);
        if (opts.saturationSet != null) p.setSaturation(opts.saturationSet);
        else if (opts.saturation != null) p.setSaturation((float) (p.getSaturation() + opts.saturation));
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
        ExoticGardenHook.onDrink(p, this.getId());

        if (opts.message != null) p.sendMessage(opts.message);
        // 音效：silent=不播；自定义 sound（喝汤播喝音/香烟打火石音等）；默认吃音效
        if (!opts.silent) {
            p.getWorld().playSound(p.getLocation(),
                    opts.sound != null ? opts.sound : Sound.ENTITY_STRIDER_EAT, 1f, 1f);
        }
    }
}
