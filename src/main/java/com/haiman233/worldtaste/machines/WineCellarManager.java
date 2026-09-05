package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.HashMap;
import java.util.Map;

/**
 * 酒窖管理器（machines.yml 中 script: jiujiao，材质酿造台）：酒窖多方块核心。
 *
 * <p>储电 500J，耗电 100J/t（仅多方块结构完整且蓄电充足时自 charge 扣除，
 * 电网会自动向其补电）。右键经 {@link BlockMenuPreset} 原生打开机器页面
 * （{@link CellarMenu}，结构与本体机器一致；结构不完整时页面内可查看多方块结构展示）。
 * 运行中双机（管理器/温控器）任一蓄电不足 → 酿造/陈化直接失败产生废液。</p>
 *
 * <p>耗电量经 {@link PowerConsumer} 暴露，注册时自动写入物品 lore。</p>
 */
public class WineCellarManager extends SlimefunItem implements EnergyNetComponent, PowerConsumer {

    public static final int CAPACITY = 500;
    public static final int CONSUMPTION = 100;

    /** 结构校验缓存：partner() 一次调用替代逐 tick 的 matches()+partner() 双重 3×3×3 扫描。 */
    private record StructureCheck(long expireAtTick, Block partner, TemperatureController ctrl) {}

    private static final Map<World, Map<Long, StructureCheck>> CHECKS = new HashMap<>();

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFF);
    }

    /** 结构被破坏时失效该位置的校验缓存（防止缓存窗口内向已拆除方块写电）。 */
    public static void invalidateStructureCheck(Block b) {
        Map<Long, StructureCheck> m = CHECKS.get(b.getWorld());
        if (m != null) m.remove(key(b));
    }

    /** 缓存式结构校验：结构完整缓存 20 tick，不完整缓存 10 tick（补建后尽快恢复计时）。 */
    private static StructureCheck structureCheck(Block b) {
        World w = b.getWorld();
        long now = w.getGameTime();
        Map<Long, StructureCheck> m = CHECKS.get(w);
        StructureCheck v = m == null ? null : m.get(key(b));
        if (v == null || v.expireAtTick() <= now) {
            Block partner = CellarStructure.partner(b, false);
            TemperatureController ctrl = partner != null
                    && me.mrCookieSlime.Slimefun.api.BlockStorage.check(partner) instanceof TemperatureController c
                    ? c : null;
            v = new StructureCheck(now + (ctrl != null ? 20 : 10), ctrl != null ? partner : null, ctrl);
            CHECKS.computeIfAbsent(w, x -> new HashMap<>()).put(key(b), v);
        }
        return v;
    }

    public WineCellarManager(ItemGroup group, SlimefunItemStack item, RecipeType rt,
                             org.bukkit.inventory.ItemStack[] recipe) {
        super(group, item, rt, recipe);

        addItemHandler(new BlockTicker() {
            @Override
            public boolean isSynchronized() { return true; }

            @Override
            public void tick(Block b, SlimefunItem sf, Config data) {
                WineCellarState st = WineCellarState.get(b);
                if (st.phase() != WineCellarState.Phase.RUNNING) return;
                StructureCheck check = structureCheck(b);
                if (check.ctrl() == null) return;
                Block partner = check.partner();
                TemperatureController ctrl = check.ctrl();
                // 断电：酿造/陈化直接失败，液体报废（结构被拆仍为暂停，破坏事件本身会清数据）
                if (getCharge(b.getLocation()) < CONSUMPTION
                        || ctrl.getCharge(partner.getLocation())
                                < TemperatureController.CONSUMPTION) {
                    boolean brew = st.mode() == WineCellarState.Mode.BREW;
                    st.contaminate();
                    st.save(b);
                    b.getWorld().playSound(b.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
                    Player placer = st.placerId() != null ? Bukkit.getPlayer(st.placerId()) : null;
                    if (placer != null) {
                        placer.sendMessage("§c酒窖电力中断，" + (brew ? "酿造" : "陈化")
                                + "失败，液体已全部报废！");
                    }
                    return;
                }
                // 双机电力协调：本轮从管理器与温控器各扣除消耗
                removeCharge(b.getLocation(), CONSUMPTION);
                ctrl.removeCharge(partner.getLocation(), TemperatureController.CONSUMPTION);
                CellarMenu.advance(st, b);
            }
        });

        // 机器页面（粘液原生 BlockMenuPreset：右键打开、界面内容随方块持久化、多玩家共享查看）。
        // 本分支 BlockMenuPreset 构造即自注册进注册表（不经过 addItemHandler）
        new BlockMenuPreset(item.getItemId(), ChatColor.GOLD + "酒窖管理器") {
            @Override
            public void init() {
                CellarMenu.setupMenu(this);
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                return true;
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return new int[0];
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                CellarMenu.onNewInstance(menu, b);
            }
        };
    }

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    @Override
    public int getConsumption() {
        return CONSUMPTION;
    }
}
