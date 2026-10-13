package com.haiman233.worldtaste.util;

import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 反射调用服务端 {@code ServerPlayerGameMode.useItem(...)}：把一次交互「转成对空使用」。
 *
 * <p>背景（食物原版进食模式）：CraftBukkit 在 {@code PlayerInteractEvent} 的
 * {@code useInteractedBlock=DENY} 时会<b>提前返回</b>，不再继续 useItem 路径——因此
 * 「DENY 方块 + ALLOW 物品」只会挡住放置，<b>不会</b>让原版 consumable 组件开始进食
 * （指向方块时客户端预测的是放置，也不会主动补发对空使用包）。要实现「指向地面也进食」，
 * 需要主动补一次 useItem 调用，等价于客户端对空气右键的服务端处理流程。</p>
 *
 * <p>Paper 运行时为 Mojang 映射，按字段/方法名反射即可；全量特征探测，
 * 不可用时返回 false（降级为「仅指向空气/无交互方块可进食」），不抛异常不刷屏。</p>
 */
public final class ServerInteract {

    private ServerInteract() {}

    private static boolean probed;
    private static boolean available;
    private static Method getHandle, useItem, asNMSCopy;
    private static java.lang.reflect.Field gameModeField;
    private static Class<?> handCls;

    private static void probe() {
        if (probed) return;
        probed = true;
        try {
            Class<?> levelCls = Class.forName("net.minecraft.world.level.Level");
            Class<?> nmsItemCls = Class.forName("net.minecraft.world.item.ItemStack");
            handCls = Class.forName("net.minecraft.world.InteractionHand");
            getHandle = org.bukkit.entity.Player.class.getMethod("getHandle");
            Class<?> handleCls = getHandle.getReturnType();
            gameModeField = handleCls.getField("gameMode");
            Class<?> gameModeCls = gameModeField.getType();
            useItem = gameModeCls.getMethod("useItem", handleCls, levelCls, nmsItemCls, handCls);
            asNMSCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack")
                    .getMethod("asNMSCopy", ItemStack.class);
            available = true;
        } catch (Throwable t) {
            available = false;
            com.haiman233.worldtaste.WT.log("ServerInteract: 服务端 useItem 反射不可用（"
                    + t.getClass().getSimpleName() + "），指向方块时将无法触发原版进食");
        }
    }

    /**
     * 以玩家当前对应手持的物品模拟一次「对空使用」——服务端会照常处理 PlayerInteractEvent
     * (RIGHT_CLICK_AIR) 并进入 consumable 进食流程。
     *
     * @return 是否成功发起（false = 反射不可用）
     */
    public static boolean forceAirUse(Player p, EquipmentSlot hand) {
        probe();
        if (!available) return false;
        try {
            Object handle = getHandle.invoke(p);
            Object gameMode = gameModeField.get(handle);
            Object level = handle.getClass().getMethod("level").invoke(handle);
            ItemStack held = hand == EquipmentSlot.OFF_HAND
                    ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
            if (held == null || held.getType().isAir()) return false;
            Object nmsStack = asNMSCopy.invoke(null, held);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object nmsHand = Enum.valueOf((Class<? extends Enum>) handCls, hand.name());
            useItem.invoke(gameMode, handle, level, nmsStack, nmsHand);
            return true;
        } catch (Throwable t) {
            com.haiman233.worldtaste.WT.log("ServerInteract.forceAirUse 失败: " + t);
            return false;
        }
    }
}
