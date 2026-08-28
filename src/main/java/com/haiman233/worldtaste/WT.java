package com.haiman233.worldtaste;

import com.haiman233.worldtaste.machines.MenuDef;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** 全局上下文：持有插件引用与各注册表，供各 loader 共享。 */
public final class WT {

    private WT() {}

    public static WorldTastePlugin plugin;

    /** id(小写) -> ItemGroup */
    public static final Map<String, ItemGroup> groups = new HashMap<>();
    /** id(大写) -> 自定义 RecipeType（来自 recipe_types.yml） */
    public static final Map<String, RecipeType> recipeTypes = new HashMap<>();
    /** id(大写) -> 预加载的展示物品（供跨文件 material_type:slimefun 引用解析） */
    public static final Map<String, ItemStack> preload = new HashMap<>();
    /** id -> 菜单定义（来自 menus.yml，供机器 GUI 使用） */
    public static final Map<String, MenuDef> menus = new HashMap<>();

    public static ItemGroup group(String id) {
        if (id == null) return null;
        return groups.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public static void log(String msg) {
        if (plugin != null) plugin.getLogger().warning(msg);
    }
}