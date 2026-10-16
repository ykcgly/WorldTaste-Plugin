package com.haiman233.worldtaste.util;

import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 机器配方输入匹配（合成匹配 / 复验 / 配方补全三处共用的统一比较入口）。
 *
 * <p>常规输入走 {@link SlimefunUtils#isItemSimilar(ItemStack, ItemStack, boolean)}；
 * 两类特例：</p>
 * <ul>
 *   <li><b>实体桶</b>（{@link #ENTITY_BUCKETS}）：玩家实际捕获的桶带有
 *       {@code bucket_entity_data} 组件（记录河豚/鱼的生命值等），与配方模板的
 *       「干净桶」严格比较必失配——对这类材质改用宽松比较：材质 + 显示名 + lore
 *       一致即匹配，忽略全部运行期组件（含 bucket_entity_data）。全程无反射、
 *       不触碰 DataComponent API（1.20 全区间可用）。显示名/lore 仍严格比较，
 *       改过名的桶不会混入；</li>
 *   <li><b>耐久工具</b>（配方声明 damage&gt;0，如钓竿）：忽略损耗值匹配
 *       （{@link #toolLike}）。</li>
 * </ul>
 */
public final class RecipeMatch {

    /** 携带 bucket_entity_data 组件的实体桶材质（1.20.x 全部存在）。 */
    public static final Set<Material> ENTITY_BUCKETS = EnumSet.of(
            Material.TROPICAL_FISH_BUCKET, Material.PUFFERFISH_BUCKET, Material.SALMON_BUCKET,
            Material.COD_BUCKET, Material.AXOLOTL_BUCKET, Material.TADPOLE_BUCKET);

    private RecipeMatch() {}

    /**
     * 配方输入统一匹配入口。
     *
     * @param in         输入槽实际物品（可能是 ItemStackWrapper 快照）
     * @param need       配方模板物品
     * @param damageTool 配方对该输入项声明了耐久损耗（damage&gt;0）时按工具匹配
     */
    public static boolean input(ItemStack in, ItemStack need, boolean damageTool) {
        if (in == null || need == null) return false;
        if (damageTool) return toolLike(in, need);
        if (ENTITY_BUCKETS.contains(need.getType())) return entityBucketLike(in, need);
        return SlimefunUtils.isItemSimilar(in, need, true);
    }

    /**
     * 实体桶宽松比较：材质 + 显示名 + lore 一致即匹配，忽略 bucket_entity_data 等
     * 运行期组件（捕获时间/生命值随个体而异，不应参与配方判定）。
     */
    public static boolean entityBucketLike(ItemStack in, ItemStack need) {
        if (in == null || need == null || in.getType() != need.getType()) return false;
        return Objects.equals(displayNameOf(in), displayNameOf(need))
                && Objects.equals(loreOf(in), loreOf(need));
    }

    /**
     * 耐久类工具输入比较：忽略损耗值（受损钓竿仍匹配新钓竿模板）。
     * 同材质（粘液物品按 id 一致）即匹配；模板带显示名时要求名称一致。
     */
    public static boolean toolLike(ItemStack in, ItemStack need) {
        if (in == null || need == null || in.getType() != need.getType()) return false;
        io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfNeed =
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(need);
        if (sfNeed != null) {
            io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sfIn =
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(in);
            return sfIn != null && sfIn.getId().equals(sfNeed.getId());
        }
        if (need.hasItemMeta() && need.getItemMeta().hasDisplayName()) {
            org.bukkit.inventory.meta.ItemMeta m = in.getItemMeta();
            return m != null && m.hasDisplayName()
                    && m.getDisplayName().equals(need.getItemMeta().getDisplayName());
        }
        return true;
    }

    private static String displayNameOf(ItemStack it) {
        if (!it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : null;
    }

    private static List<String> loreOf(ItemStack it) {
        if (!it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        return meta != null && meta.hasLore() ? meta.getLore() : null;
    }
}
