package com.haiman233.worldtaste.compat.legacy;

import com.haiman233.worldtaste.compat.ViewCompat;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * legacy（1.20.x ~ 1.21.0 前）InventoryView 实现：Paper 1.20.6 编译，
 * {@code InventoryView} 在该版本段为<b>抽象类</b>，{@code getTopInventory()} 编译为
 * invokevirtual——与 1.20.x 运行时形态一致，直调即可（1.21+ 运行时不会加载本类）。
 */
public final class LegacyViewCompat implements ViewCompat {

    public static final LegacyViewCompat INSTANCE = new LegacyViewCompat();

    private LegacyViewCompat() {}

    @Override
    public Inventory top(Object view) {
        if (!(view instanceof InventoryView iv)) return null;
        return iv.getTopInventory();
    }
}
