package com.haiman233.worldtaste.util;

import java.lang.reflect.Method;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * {@code InventoryView} 跨版本工具（MC 1.21 起由抽象类改为接口）。
 *
 * <p>对 view 直接调用方法在另一侧运行时会抛 {@link IncompatibleClassChangeError}
 * （编译期按接口生成 invokeinterface、老服务端却是抽象类，反之亦然），因此对 view 的
 * 方法调用一律经反射完成。Method 从公共的 {@link InventoryView} 类型解析，
 * 两种版本形态下均可正常调用并缓存复用。</p>
 */
public final class Views {

    private static volatile Method topMethod;

    private Views() {}

    /**
     * 反射获取 view 的顶部容器。
     *
     * @param view {@code Player#getOpenInventory()} / {@code openAnvil} 等返回的 view 对象
     * @return 顶部容器；view 为 null 或反射失败时返回 null
     */
    public static Inventory top(Object view) {
        if (view == null) return null;
        try {
            Method m = topMethod;
            if (m == null) {
                m = InventoryView.class.getMethod("getTopInventory");
                topMethod = m;
            }
            return (Inventory) m.invoke(view);
        } catch (Throwable t) {
            return null;
        }
    }
}
