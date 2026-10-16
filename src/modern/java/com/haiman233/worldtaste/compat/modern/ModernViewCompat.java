package com.haiman233.worldtaste.compat.modern;

import com.haiman233.worldtaste.compat.ViewCompat;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * modern（1.21+，主体）InventoryView 实现：Paper 1.21.11 编译，{@code InventoryView}
 * 在该版本段为<b>接口</b>，方法调用编译为 invokeinterface——与 1.21+ 运行时形态一致，
 * 直调即可（1.20.x 运行时不会加载本类）。
 */
public final class ModernViewCompat implements ViewCompat {

    public static final ModernViewCompat INSTANCE = new ModernViewCompat();

    private ModernViewCompat() {}

    @Override
    public Inventory top(Object view) {
        return view instanceof InventoryView iv ? iv.getTopInventory() : null;
    }
}
