package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.ViewCompat;
import java.lang.reflect.Method;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * legacy（1.20.x ~ 1.21.1）InventoryView 实现：反射调用（Method 从公共 InventoryView
 * 类型解析，两种版本形态——抽象类/接口——下均可正常调用并缓存复用）。
 */
public final class LegacyViewCompat implements ViewCompat {

    public static final LegacyViewCompat INSTANCE = new LegacyViewCompat();

    private LegacyViewCompat() {}

    private volatile Method topMethod;

    @Override
    public Inventory top(Object view) {
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
