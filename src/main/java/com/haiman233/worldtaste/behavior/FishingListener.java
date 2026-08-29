package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.load.Yaml;
import com.haiman233.worldtaste.util.Stacks;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 百味钓竿钓鱼（对齐原 diaoyu.js + wt_fishing.js）：主手持指定钓竿、副手持已知鱼饵时，
 * 取消原掉落、消耗 1 鱼饵、按权重随机产出 1 个物品并拉向玩家。
 */
public final class FishingListener implements Listener {

    public static final FishingListener INSTANCE = new FishingListener();

    private static String rodId = "WT_BAIWEIDIAOGAN";
    private static final Map<String, Bait> baits = new HashMap<>();

    private FishingListener() {}

    public static void load() {
        YamlConfiguration y = Yaml.loadResource(WT.plugin, "data/fishing.yml");
        rodId = y.getString("rod", rodId);
        ConfigurationSection bs = y.getConfigurationSection("baits");
        baits.clear();
        if (bs != null) {
            for (String bait : bs.getKeys(false)) {
                List<Drop> drops = new ArrayList<>();
                for (Map<?, ?> m : bs.getMapList(bait)) {
                    Object id = m.get("id");
                    Object w = m.get("weight");
                    if (id instanceof String && w instanceof Number) {
                        drops.add(new Drop((String) id, ((Number) w).intValue()));
                    }
                }
                baits.put(bait, new Bait(drops));
            }
        }
        int total = baits.values().stream().mapToInt(b -> b.drops.size()).sum();
        WT.plugin.getLogger().info("行为数据: fishing rod=" + rodId + " baits=" + baits.size() + " drops=" + total);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || e.getCaught() == null) return;
        Player p = e.getPlayer();
        SlimefunItem rod = SlimefunItem.getByItem(p.getInventory().getItemInMainHand());
        if (rod == null || !rod.getId().equals(rodId)) return;
        SlimefunItem bait = SlimefunItem.getByItem(p.getInventory().getItemInOffHand());
        if (bait == null) return;
        Bait table = baits.get(bait.getId());
        if (table == null) return;

        // 先选并解析掉落物；无法解析（如未装对应附属）时不取消事件、不扣饵、保留原渔获
        Drop d = select(table);
        if (d == null) return;
        ItemStack stack = resolve(d.id);
        if (stack == null) return;

        e.setCancelled(true);
        // 鱼饵耗尽到 0 必须清空副手槽位：否则残留 0 数量幽灵物品仍被识别为该鱼饵，
        // 玩家可无消耗无限钓获（复制漏洞）。
        Stacks.consumeOneInOffHand(p.getInventory());
        e.getCaught().remove();
        // 关键：取消 CAUGHT_FISH 后必须彻底移除鱼钩实体——
        // Paper 的 FishingHook.catchFish 在事件取消时不会自动收回钩子：仅清"持有鱼"状态
        // 会让钩子残留水中继续 tick（浮漂不收回、旧钩子持续触发 CAUGHT_FISH），
        // 导致"连续收杆抛竿可多次收获"；remove() 后玩家下次右键会自动复位 fishing 引用并抛新竿。
        e.getHook().remove();

        stack.setAmount(1);
        // 产物优先进背包；背包满时以掉落物形态掉落在玩家位置
        java.util.Map<Integer, ItemStack> leftover = p.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            Item ent = p.getWorld().dropItem(p.getLocation().add(0, 1, 0), leftover.get(0));
            ent.setPickupDelay(2);
        }
        p.sendMessage("§b恭喜你钓到了 " + displayName(stack) + " §b*1");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    /** 按权重随机选 1 个掉落。total 在 load 期预算（消除每次钓获的求和）。 */
    private static Drop select(Bait table) {
        if (table.total <= 0) return null;
        double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * table.total;
        for (Drop d : table.drops) {
            r -= d.weight;
            if (r <= 0) return d;
        }
        return table.drops.get(table.drops.size() - 1);
    }

    private static ItemStack resolve(String id) {
        SlimefunItem sf = SlimefunItem.getById(id);
        if (sf != null) return sf.getItem().clone();
        Material m = Material.matchMaterial(id);
        return m == null ? null : new ItemStack(m);
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return stack.getItemMeta().getDisplayName();
        }
        return stack.getType().name().toLowerCase().replace('_', ' ');
    }

    private static final class Drop {
        final String id;
        final int weight;
        Drop(String id, int weight) { this.id = id; this.weight = weight; }
    }

    /** 一个鱼饵的掉落表 + 预算权重总和（load 期一次计算，select 直接用，消除每次钓获的求和）。 */
    private static final class Bait {
        final List<Drop> drops;
        final int total;
        Bait(List<Drop> drops) {
            this.drops = drops;
            int t = 0;
            for (Drop d : drops) t += d.weight;
            this.total = t;
        }
    }
}
