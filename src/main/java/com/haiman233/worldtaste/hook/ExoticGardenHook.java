package com.haiman233.worldtaste.hook;

import com.haiman233.worldtaste.WT;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 异域花园（ExoticGarden·复合花园 fork）酒精度联动。
 *
 * <p>异域花园的酒精系统：{@code ExoticGarden.drunkPlayers}（玩家名 → PlayerAlcohol）按玩家累计
 * 酒精度，每 6 秒衰减 1 点，达到 100 进入醉酒（反胃/走路打晃/胡言乱语）。其酒类物品饮用时经
 * {@code CustomWine#restoreHunger} 累加数值并按阈值提示半醉/醉酒。</p>
 *
 * <p>本插件不硬依赖异域花园（plugin.yml 仅 softdepend），故全部走反射：物品注册期把
 * items.yml 的 alcohol 数值登记到 {@link #alcoholValues}，消耗品饮用时经
 * {@link #onDrink} 累加到异域花园并复刻其半醉/醉酒提示。异域花园未安装或其酒精系统
 * 缺失时静默降级（酒精度 lore 仅作风味文本）。</p>
 */
public final class ExoticGardenHook {

    private ExoticGardenHook() {}

    /** Slimefun 物品 id -> 酒精度（items.yml alcohol 字段，ItemsLoader 注册期写入）。 */
    private static final Map<String, Integer> alcoholValues = new HashMap<>();

    private static volatile boolean probed;
    private static boolean available;

    private static Field instanceField;
    private static Field drunkPlayersField;
    private static Method initPlayerData;
    private static Method sendDrunkMessage;
    private static Method addAlcohol;
    private static Method getAlcohol;

    /** 登记酒精度（物品 id 与注册 id 一致；重复注册以最后一次为准）。 */
    public static void register(String itemId, int alcohol) {
        if (itemId != null && alcohol > 0) alcoholValues.put(itemId, alcohol);
    }

    public static int registeredCount() {
        return alcoholValues.size();
    }

    /** 启动期探测异域花园酒精系统并输出联动日志（结果缓存，运行期 available/onDrink 复用）。 */
    public static void init() {
        available();
    }

    /** 异域花园酒精系统是否可用（首次调用探测并缓存）。 */
    public static boolean available() {
        if (!probed) {
            synchronized (ExoticGardenHook.class) {
                if (!probed) {
                    try {
                        probe();
                    } finally {
                        probed = true;
                    }
                }
            }
        }
        return available;
    }

    private static void probe() {
        if (Bukkit.getPluginManager().getPlugin("ExoticGarden") == null) {
            WT.plugin.getLogger().info("未检测到异域花园，酒精度联动未启用");
            return;
        }
        try {
            Class<?> eg = Class.forName("io.github.thebusybiscuit.exoticgarden.ExoticGarden");
            Class<?> pa = Class.forName("io.github.thebusybiscuit.exoticgarden.PlayerAlcohol");
            instanceField = eg.getField("instance");
            drunkPlayersField = eg.getField("drunkPlayers");
            initPlayerData = eg.getMethod("initPlayerData", Player.class);
            sendDrunkMessage = eg.getMethod("sendDrunkMessage", Player.class);
            addAlcohol = pa.getMethod("addAlcohol", int.class);
            getAlcohol = pa.getMethod("getAlcohol");
            available = true;
            WT.plugin.getLogger().info("已联动异域花园酒精度系统（" + alcoholValues.size() + " 种酒类饮品）");
        } catch (Throwable t) {
            available = false;
            // 异域花园已安装但酒精系统不可用（异常 fork/结构变更）：一次性告警便于排查
            WT.log("检测到异域花园但酒精系统反射失败，酒精度联动未启用: " + t);
        }
    }

    /**
     * 饮用酒类后累加异域花园酒精度，并按异域花园阈值提示（对齐 CustomWine：50~99 半醉、>=100 醉酒+胡言乱语）。
     * 非酒类物品（无登记数值）或联动不可用时为无副作用空操作。
     */
    public static void onDrink(Player p, String itemId) {
        Integer amount = itemId == null ? null : alcoholValues.get(itemId);
        if (amount == null || amount <= 0) return;
        if (!available()) return;
        try {
            Object eg = instanceField.get(null);
            if (eg == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> drunk = (Map<String, Object>) drunkPlayersField.get(null);
            Object pa = drunk.get(p.getName());
            if (pa == null) {
                // 玩家数据缺失（异域花园未初始化该玩家）：走其官方加载路径补齐
                initPlayerData.invoke(eg, p);
                pa = drunk.get(p.getName());
                if (pa == null) return;
            }
            addAlcohol.invoke(pa, amount);
            int now = (Integer) getAlcohol.invoke(pa);
            if (now < 100 && now > 50) {
                p.sendMessage("§8[§a异域花园§8] §e你已经半醉了，请适度饮酒！");
            } else if (now >= 100) {
                p.sendMessage("§8[§a异域花园§8] §e你醉了！可以尝试食用一些可以§b解酒§e的消耗品");
                sendDrunkMessage.invoke(null, p);
            }
        } catch (Throwable t) {
            WT.log("异域花园酒精度累加失败: " + t);
        }
    }
}
