package com.haiman233.worldtaste.compat.modern;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

/**
 * 1.21.4+ 专属食物实现（{@link ModernFoodCompat} 子类）：头颅外观注入。
 *
 * <p>{@code item_model} 组件（1.21.4 新增）允许任意物品使用任意物品模型——头颅类食物
 * 以 NETHER_BRICK 等非方块物品为本体、渲染为头颅外观后，右键任何位置原版都会直接
 * 开始进食（本体不是方块物品，不存在「放置打断进食」的交互争抢）。</p>
 *
 * <p><b>类加载安全</b>：{@code DataComponentTypes.ITEM_MODEL} 字段在 1.21.3- 服务端的
 * jar 中不存在，静态引用会在字段首次解析时抛 {@link NoSuchFieldError}。本类仅由
 * {@code Compat} 的静态 Holder 在 {@code ≥1.21.4} 路由分支中被引用（与既有惰性加载
 * 模式一致），低版本永远不会加载/验证本类。</p>
 */
public final class ModernHeadFoodCompat extends ModernFoodCompat {

    public static final ModernHeadFoodCompat INSTANCE = new ModernHeadFoodCompat();

    private ModernHeadFoodCompat() {}

    @Override
    public boolean supportsHeadModel() {
        return true;
    }

    @Override
    public boolean applyHeadModel(ItemStack stack, ItemStack headSource) {
        if (stack == null || headSource == null) return false;
        try {
            // profile 组件从头颅物品读取（Read.item 构造的 SkullMeta 在组件化时代即
            // profile 组件的视图）；取不到（非头颅来源等）时回退头颅本体构造
            ResolvableProfile profile = headSource.getData(DataComponentTypes.PROFILE);
            if (profile == null) return false;
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft", "player_head"));
            stack.setData(DataComponentTypes.PROFILE, profile);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
