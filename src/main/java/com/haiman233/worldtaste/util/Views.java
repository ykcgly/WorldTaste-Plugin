package com.haiman233.worldtaste.util;

import com.haiman233.worldtaste.compat.Compat;
import org.bukkit.inventory.Inventory;

/**
 * {@code InventoryView} 跨版本工具（MC 1.21 起由抽象类改为接口）。
 *
 * <p>对 view 直接调用方法在另一侧运行时会抛 {@link IncompatibleClassChangeError}
 * （编译期按接口生成 invokeinterface、老服务端却是抽象类，反之亦然）。实现经
 * {@link Compat#views()} 路由：modern（1.21.2+）接口直调、legacy 反射，
 * 本类只保留稳定的调用入口。</p>
 */
public final class Views {

    private Views() {}

    /**
     * 获取 view 的顶部容器。
     *
     * @param view {@code Player#getOpenInventory()} / {@code openAnvil} 等返回的 view 对象
     * @return 顶部容器；view 为 null 或失败时返回 null
     */
    public static Inventory top(Object view) {
        return Compat.views().top(view);
    }
}
