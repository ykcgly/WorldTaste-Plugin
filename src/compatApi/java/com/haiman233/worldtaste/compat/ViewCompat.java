package com.haiman233.worldtaste.compat;

import org.bukkit.inventory.Inventory;

/**
 * {@code InventoryView} 兼容层（MC 1.21 起由抽象类改为接口，方法调用字节码两版不兼容）。
 * 实现见 {@code compat.legacy.LegacyViewCompat}（反射）与
 * {@code compat.modern.ModernViewCompat}（接口直调）。
 */
public interface ViewCompat {

    /**
     * 获取 view 的顶部容器。
     *
     * @param view {@code Player#getOpenInventory()} / {@code openAnvil} 等返回的 view 对象
     * @return 顶部容器；view 为 null 或失败时返回 null
     */
    Inventory top(Object view);
}
