package com.haiman233.worldtaste.behavior;

import com.haiman233.worldtaste.load.MobDropsLoader;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/** 生物死亡掉落（对齐原 mob_drops.yml 的 entity+chance）。 */
public final class MobDropListener implements Listener {

    public static final MobDropListener INSTANCE = new MobDropListener();

    private MobDropListener() {}

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        // 按实体类型直接查表，O(该类型掉落数) 而非 O(全部掉落)
        List<MobDropsLoader.Drop> list = MobDropsLoader.drops.get(e.getEntityType().name());
        if (list == null) return;
        for (MobDropsLoader.Drop d : list) {
            if (ThreadLocalRandom.current().nextInt(100) < d.chance) {
                // 克隆预构建的普通 ItemStack 模板：不走 SlimefunItemStack 构造链，
                // 头颅 Profile 也只在首次构建时解析一次（spark 热点修复）
                ItemStack template = d.template();
                if (template != null) e.getDrops().add(template.clone());
            }
        }
    }
}
