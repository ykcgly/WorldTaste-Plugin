package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 酒窖状态机（按管理器方块位置存储）。
 *
 * <p>阶段：IDLE（投料）→ RUNNING（酿造/陈化计时，双机耗电）→ READY（出酒）；
 * RUNNING 中酿造模式手动关闭 → CONTAMINATED（液体报废，黄色玻璃板清空后回 IDLE），
 * 陈化模式手动关闭 → READY（停止计时，可出酒）。</p>
 *
 * <p>液位：容量 24 单位（瓶装 1/单位、桶装 3/单位）。液体按「同组成 + 同单份糖分」合并为
 * 批次（累加单位数、并集榨汁师）；酒精度为整池强度值（每瓶一致）。状态经 BlockStorage
 * 持久化，重启后由 {@link #get} 惰性重建。</p>
 */
public final class WineCellarState {

    public static final int CAPACITY = 24;

    public enum Phase { IDLE, RUNNING, READY, CONTAMINATED }

    public enum Mode { BREW, AGE }

    /** 液体批次：同组成同糖分的单位合并。 */
    public static final class Liquid {
        private final Map<String, Integer> contents; // ref -> 数量
        private final int sugarPerUnit;
        private int units;
        private final Set<String> players;

        Liquid(Map<String, Integer> contents, int sugarPerUnit, int units, Set<String> players) {
            this.contents = contents;
            this.sugarPerUnit = sugarPerUnit;
            this.units = units;
            this.players = players;
        }

        public Map<String, Integer> contents() { return contents; }
        public int sugarPerUnit() { return sugarPerUnit; }
        public int units() { return units; }
        public Set<String> players() { return players; }

        String key() {
            return JuicerRecipe.joinContents(contents) + "|" + sugarPerUnit;
        }
    }

    private Phase phase = Phase.IDLE;
    private Mode mode = Mode.BREW;
    private long elapsedMs;
    private long durationMs;
    private double alcohol;
    private long nextGrowthAt;
    private String yeast;
    private String placerName;
    private UUID placerId;
    private final List<Liquid> liquids = new ArrayList<>();
    private int units;

    // ===== 访问器 =====
    public Phase phase() { return phase; }
    public void phase(Phase phase) { this.phase = phase; }
    public Mode mode() { return mode; }
    public void toggleMode() { mode = mode == Mode.BREW ? Mode.AGE : Mode.BREW; }
    public long elapsedMs() { return elapsedMs; }
    public void elapsedMs(long v) { elapsedMs = v; }
    public long durationMs() { return durationMs; }
    public double alcohol() { return alcohol; }
    public void alcohol(double v) { alcohol = v; }
    public long nextGrowthAt() { return nextGrowthAt; }
    public void nextGrowthAt(long v) { nextGrowthAt = v; }
    public String yeast() { return yeast; }
    public void yeast(String yeast) { this.yeast = yeast; }
    public UUID placerId() { return placerId; }
    public int units() { return units; }
    public List<Liquid> liquids() { return liquids; }
    public boolean hasAlcohol() { return alcohol > 0; }
    public boolean canAccept(int add) { return units + add <= CAPACITY; }

    /** 游戏日时长（陈化增长里程碑）。 */
    public static final long GAME_DAY_MS = 24 * 60_000L;

    /** 启动运行（酿造传目标时长，陈化传 0），重置计时。 */
    public void startRun(Player p, long duration) {
        startedBy(p);
        phase = Phase.RUNNING;
        elapsedMs = 0;
        durationMs = duration;
        nextGrowthAt = GAME_DAY_MS;
    }

    /** 记录启动者。 */
    public void startedBy(Player p) {
        this.placerName = p.getName();
        this.placerId = p.getUniqueId();
    }

    /** 投入液体（同组成同糖分合并，榨汁师并集）。 */
    public void addLiquid(Map<String, Integer> contents, int sugarPerUnit, int add, String player) {
        String key = JuicerRecipe.joinContents(contents) + "|" + sugarPerUnit;
        for (Liquid lq : liquids) {
            if (lq.key().equals(key)) {
                lq.units += add;
                if (player != null && !player.isEmpty()) lq.players.add(player);
                units += add;
                return;
            }
        }
        Set<String> players = new LinkedHashSet<>();
        if (player != null && !player.isEmpty()) players.add(player);
        liquids.add(new Liquid(new LinkedHashMap<>(contents), sugarPerUnit, add, players));
        units += add;
    }

    /** 出酒消耗 1 单位。 */
    public void drainUnit() {
        for (Liquid lq : liquids) {
            if (lq.units > 0) {
                lq.units--;
                units--;
                return;
            }
        }
    }

    /** 全部液体合并后的组成（ref → 数量）。 */
    public Map<String, Integer> contentsOfAll() {
        Map<String, Integer> all = new LinkedHashMap<>();
        for (Liquid lq : liquids) {
            for (Map.Entry<String, Integer> e : lq.contents().entrySet()) {
                all.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return all;
    }

    /** 全部榨汁师并集。 */
    public Set<String> allPlayers() {
        Set<String> all = new LinkedHashSet<>();
        for (Liquid lq : liquids) all.addAll(lq.players);
        return all;
    }

    /** 整池总糖分 = Σ 单份糖分 × 单位数。 */
    public int totalSugar() {
        int total = 0;
        for (Liquid lq : liquids) total += lq.sugarPerUnit * lq.units;
        return total;
    }

    /** 清空液体与酒曲（污染恢复/出酒完毕）。 */
    public void clear() {
        liquids.clear();
        units = 0;
        yeast = null;
        alcohol = 0;
        elapsedMs = 0;
        durationMs = 0;
        nextGrowthAt = 0;
        phase = Phase.IDLE;
        placerName = null;
        placerId = null;
    }

    /** 酿造中报废。 */
    public void contaminate() {
        phase = Phase.CONTAMINATED;
        liquids.clear();
        units = 0;
        yeast = null;
        alcohol = 0;
    }

    // ===== 注册表与持久化 =====
    private static final Map<Location, WineCellarState> STATES = new HashMap<>();

    public static WineCellarState get(Block b) {
        Location l = b.getLocation();
        WineCellarState st = STATES.get(l);
        if (st != null) return st;
        st = new WineCellarState();
        deserialize(st, BlockStorage.getLocationInfo(l, "wt-cellar-data"));
        STATES.put(l, st);
        return st;
    }

    public static void remove(Block b) {
        STATES.remove(b.getLocation());
    }

    /** 持久化当前状态（每次变更后调用）。 */
    public void save(Block b) {
        StringBuilder sb = new StringBuilder();
        sb.append(phase.name()).append('|').append(mode.name()).append('|').append(elapsedMs)
          .append('|').append(durationMs).append('|').append(alcohol).append('|').append(nextGrowthAt)
          .append('|').append(yeast == null ? "" : yeast)
          .append('|').append(placerName == null ? "" : placerName)
          .append('|').append(placerId == null ? "" : placerId);
        for (Liquid lq : liquids) {
            sb.append(';').append(lq.units).append('|').append(lq.sugarPerUnit).append('|')
              .append(String.join("~", lq.players)).append('|')
              .append(JuicerRecipe.joinContents(lq.contents));
        }
        BlockStorage.addBlockInfo(b, "wt-cellar-data", sb.toString());
    }

    private static void deserialize(WineCellarState st, String data) {
        if (data == null || data.isEmpty()) return;
        String[] parts = data.split("\\|", -1);
        if (parts.length < 9) return;
        try {
            st.phase = Phase.valueOf(parts[0]);
            st.mode = Mode.valueOf(parts[1]);
            st.elapsedMs = Long.parseLong(parts[2]);
            st.durationMs = Long.parseLong(parts[3]);
            st.alcohol = Double.parseDouble(parts[4]);
            st.nextGrowthAt = Long.parseLong(parts[5]);
            st.yeast = parts[6].isEmpty() ? null : parts[6];
            st.placerName = parts[7].isEmpty() ? null : parts[7];
            st.placerId = parts[8].isEmpty() ? null : UUID.fromString(parts[8]);
            int i = 9;
            while (i + 4 <= parts.length) {
                Map<String, Integer> contents = JuicerRecipe.parseContents(parts[i + 3]);
                Set<String> players = new LinkedHashSet<>();
                for (String n : parts[i + 2].split("~")) {
                    if (!n.isEmpty()) players.add(n);
                }
                st.liquids.add(new Liquid(contents, Integer.parseInt(parts[i + 1]),
                        Integer.parseInt(parts[i]), players));
                i += 4;
            }
            st.units = 0;
            for (Liquid lq : st.liquids) st.units += lq.units;
        } catch (Exception ex) {
            WT.log("酒窖状态解析失败，已重置: " + ex);
            st.phase = Phase.IDLE;
            st.liquids.clear();
            st.units = 0;
        }
    }
}
