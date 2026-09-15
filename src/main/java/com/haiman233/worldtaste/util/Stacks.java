package com.haiman233.worldtaste.util;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** ItemStack 辅助操作。 */
public final class Stacks {

    private Stacks() {}

    /** 按「类型 + 显示名」匹配已注册 Slimefun 物品（用于还原无 id 的展示物品）。
     *  多方块/机器产出在加载期目标物品未注册（如 RegisterConditions 不满足）时会退化为
     *  preload 展示物品（缺 slimefun id PDC），导致右键食用/放置行为异常；此方法按外观反查。 */
    public static io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem findRegisteredByAppearance(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        org.bukkit.Material type = stack.getType();
        String name = null;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) name = meta.getDisplayName();
        for (io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf : io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getRegistry().getAllSlimefunItems()) {
            org.bukkit.inventory.ItemStack it = sf.getItem();
            if (it.getType() != type) continue;
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (name != null) {
                if (m != null && name.equals(m.getDisplayName())) return sf;
            } else {
                if (m == null || !m.hasDisplayName()) return sf;
            }
        }
        return null;
    }

    /** 附魔发光（隐藏附魔纹）。 */
    public static void glow(ItemStack item) {
        if (item == null) return;
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        m.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(m);
    }

    /**
     * 安全扣除主手 1 个物品：数量减到 0 时清空槽位（set null），避免残留 0 数量“幽灵物品”。
     *
     * <p>直接 {@code setAmount(amount - 1)} 到 0 时，槽位仍持有一个 0 数量的原物品引用：
     * 其类型/Slimefun 绑定不变，会被 {@code SlimefunItem.getByItem} 或 {@code getType()} 继续识别为“存在”，
     * 从而被无消耗地重复利用（钓鱼鱼饵→无限钓获、打火石→无限点烟）。到 0 必须清空槽位。</p>
     */
    public static void consumeOneInMainHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInMainHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInMainHand(null);
        else it.setAmount(left);
    }

    /** 同 {@link #consumeOneInMainHand}，作用于副手。 */
    public static void consumeOneInOffHand(PlayerInventory inv) {
        if (inv == null) return;
        ItemStack it = inv.getItemInOffHand();
        if (it == null) return;
        int left = it.getAmount() - 1;
        if (left <= 0) inv.setItemInOffHand(null);
        else it.setAmount(left);
    }
    /**
     * 对副手工具施加耐久损耗（玩家右键消耗型物品时调用）：有耐久上限且非无敌的物品扣
     * {@code amount} 点耐久并写回副手；耐久耗尽时工具损坏消失（清空副手槽位，防止 0 数量
     * 幽灵物品残留被 getType() 校验无限复用）；无敌（Unbreakable）工具不损耗也不消耗。
     *
     * @return true = 已按耐久处理（含损坏消失与无敌豁免）；false = 物品不可损耗
     *         （无耐久上限），调用方可回退整件消耗。
     */
    public static boolean damageToolInOffHand(PlayerInventory inv, int amount) {
        if (inv == null) return false;
        ItemStack it = inv.getItemInOffHand();
        if (it == null || it.getType().isAir()) return false;
        if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        if (damageable.isUnbreakable()) return true;
        int newDamage = damageable.getDamage() + Math.max(1, amount);
        if (newDamage >= max) {
            inv.setItemInOffHand(null);
            return true;
        }
        damageable.setDamage(newDamage);
        ItemStack updated = it.clone();
        updated.setItemMeta(damageable);
        inv.setItemInOffHand(updated);
        return true;
    }
}
