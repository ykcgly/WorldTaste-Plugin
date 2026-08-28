package com.haiman233.worldtaste.guide;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemState;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 装饰分隔板组（groups.yml type: button，RSC 中 actions: none 的对应物）。
 *
 * <p>两类装饰物：
 * <ul>
 *   <li>玻璃板（材质 *_STAINED_GLASS_PANE / GLASS_PANE）：清洗为「普通原版玻璃板」
 *       （无名字/无 lore/无贴图变化）；</li>
 *   <li>区段标签（头颅等其它材质，如「主题餐饮」）：保留原名字与头颅贴图，仅确保无 lore。</li>
 * </ul>
 * 两者均打上 PDC 标记：原版指南路径由 WTNestedGroup.openVanilla 渲染并绑定空点击处理器；
 * JEG 指南路径点击时触发 ItemGroupButtonClickEvent，由 JegGuideListener 依据该标记取消
 * 事件——JEG 的 EventBuilder.ifSuccess 语义为「已取消 = 点击已处理」，菜单纹丝不动，
 * 既打不开也拿不走。另外覆写 getItem(Player) 去掉 Slimefun 默认加上的
 * 「打开物品组」提示 lore（装饰板不可打开，不需要该提示）。</p>
 *
 * <p>可见性（Slimefun 的 ItemGroup.isVisible 逻辑）：
 * <ol>
 *   <li>入口检查 items 字段非空——靠构造时塞入的哑物品通过；</li>
 *   <li>遍历 getItems() 调用 item.isDisabledIn(world)——未注册物品在这里会抛
 *       IllegalArgumentException，因此哑物品绝不能出现在 getItems() 结果里：
 *       本类覆写 getItems()，惰性挑一个已注册、非隐藏、未禁用的真实物品返回。</li>
 * </ol>
 * 哑物品只进 items 字段、不注册进 Slimefun 注册表，搜索/作弊模式/指南均不可见。</p>
 */
public class DecorativeSubGroup extends SubItemGroup {

    private static NamespacedKey markKey;

    /** 清洗后的展示物品（玻璃板无名无 lore；头颅标签保留名字与贴图）。 */
    private final ItemStack displayItem;

    /** 可见性循环用的真实物品（惰性解析 + 缓存）。 */
    private SlimefunItem cachedVisibilityItem;

    public DecorativeSubGroup(NamespacedKey key, NestedItemGroup parent, ItemStack display, int tier) {
        super(key, parent, prepareDisplay(display), tier);
        this.displayItem = prepareDisplay(display);
        addVisibilityDummy();
    }

    /** 覆写掉 Slimefun 默认的「打开物品组」提示 lore——装饰板不可打开，不需要该提示。 */
    @Override
    public ItemStack getItem(Player p) {
        return displayItem.clone();
    }

    /** 识别标记 key：JegGuideListener 据此取消 JEG 的物品组点击事件。 */
    public static NamespacedKey markKey() {
        if (markKey == null) {
            markKey = new NamespacedKey(WT.plugin, "decorative_pane");
        }
        return markKey;
    }

    /**
     * 覆写可见性遍历：绝不返回未注册的哑物品（isDisabledIn 会抛异常炸掉整页渲染），
     * 改回一个已注册的真实物品。解析失败返回空列表（该组不显示，但不会炸菜单）。
     */
    @Override
    public List<SlimefunItem> getItems() {
        SlimefunItem it = resolveVisibilityItem();
        return it == null ? List.of() : List.of(it);
    }

    private SlimefunItem resolveVisibilityItem() {
        if (cachedVisibilityItem != null && cachedVisibilityItem.getState() == ItemState.ENABLED) {
            return cachedVisibilityItem;
        }
        cachedVisibilityItem = null;
        for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
            if (item.getState() == ItemState.ENABLED && !item.isHidden()) {
                cachedVisibilityItem = item;
                break;
            }
        }
        return cachedVisibilityItem;
    }

    /**
     * 塞入可见性哑物品：仅让 items 字段非空（isVisible 的入口检查），
     * 不 register 进 Slimefun 注册表；配合 getItems() 覆写，它永远不会被遍历到。
     */
    private void addVisibilityDummy() {
        try {
            String id = getKey().getKey().toUpperCase(Locale.ROOT) + "_DECOR_DUMMY";
            SlimefunItemStack stack = new SlimefunItemStack(id, prepareDisplay(new ItemStack(Material.PAPER)));
            SlimefunItem dummy = new SlimefunItem(this, stack, RecipeType.NULL, new ItemStack[9]);
            add(dummy);
        } catch (Throwable t) {
            WT.log("装饰组 " + getKey().getKey() + " 哑物品创建失败，该玻璃板可能不显示: " + t);
        }
    }

    /**
     * 展示物品清洗：玻璃板 → 无名字无 lore 的纯占位；
     * 其它（头颅区段标签等）→ 保留原名字与贴图；两者统一清 lore 并打 PDC 标记。
     */
    private static ItemStack prepareDisplay(ItemStack src) {
        ItemStack out = src == null ? new ItemStack(Material.WHITE_STAINED_GLASS_PANE) : src.clone();
        Material mat = out.getType();
        boolean isPane = mat == Material.GLASS_PANE || mat.name().endsWith("_STAINED_GLASS_PANE");
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            if (isPane) {
                meta.setDisplayName(null);
            }
            meta.setLore(null);
            meta.getPersistentDataContainer().set(markKey(), PersistentDataType.BYTE, (byte) 1);
            out.setItemMeta(meta);
        }
        return out;
    }
}