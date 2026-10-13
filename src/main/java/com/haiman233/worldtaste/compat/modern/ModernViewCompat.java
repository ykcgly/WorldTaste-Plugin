package com.haiman233.worldtaste.compat.modern;

import com.haiman233.worldtaste.compat.ViewCompat;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * modern（1.21.2+，主体）InventoryView 实现：1.21 起 {@code InventoryView} 已是接口，
 * 方法调用可直接编译为 invokeinterface，无需反射。
 */
public final class ModernViewCompat implements ViewCompat {

    public static final ModernViewCompat INSTANCE = new ModernViewCompat();

    private ModernViewCompat() {}

    @Override
    public Inventory top(Object view) {
        return view instanceof InventoryView iv ? iv.getTopInventory() : null;
    }
}
