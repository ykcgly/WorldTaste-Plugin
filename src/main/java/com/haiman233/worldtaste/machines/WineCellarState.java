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
import org.bukkit.World;
import org.bukkit.block.Block;
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

    /** 液体批次：同组成同糖分的单位合并（组成支持分数，如混合果汁每单位 2/3 苹果）。 */
    public static final class Liquid {
        private final Map<String, Double> contents; // ref → 每单位数量
        private final int sugarPerUnit;
        private int units;
        private final Set<String> players;

        Liquid(Map<String, Double> contents, int sugarPerUnit, int units, Set<String> players) {
            this.contents = contents;
            this.sugarPerUnit = sugarPerUnit;
            this.units = units;
            this.players = players;
        }

        public Map<String, Double> contents() { return contents; }
        public int sugarPerUnit() { return sugarPerUnit; }
        public int units() { return units; }
        public Set<String> players() { return players; }

        String key() {
            return JuicerRecipe.joinContentsFractional(contents) + "|" + sugarPerUnit;
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
    private int juiceUnits;  // 果汁单位数（不含清水）
    private int waterUnits;  // 清水单位数（稀释用）
    private String cellarRecipe; // 锁定的酒窖配方 key（null = 通用酒精流程）
    private int cellarMultiplier = 1;
    private boolean autoAge; // 自动陈化：酿造完成后直接进入陈化
    private String ownerName; // 放置酒窖的玩家（命名权限）
    private UUID ownerId;
    private String cellarName; // 玩家设定的酒窖名（颜色代码已翻译，null = 未命名）
    private long lastSaveMs; // 上次落盘时间（运行计时 5 分钟节流用，不持久化）

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
    public int units() { return juiceUnits + waterUnits; }
    public int juiceUnits() { return juiceUnits; }
    public int waterUnits() { return waterUnits; }
    public String cellarRecipe() { return cellarRecipe; }
    public int cellarMultiplier() { return cellarMultiplier; }
    public boolean autoAge() { return autoAge; }
    public void autoAge(boolean v) { autoAge = v; }
    public long lastSaveMs() { return lastSaveMs; }
    public String ownerName() { return ownerName; }
    public UUID ownerId() { return ownerId; }
    public String cellarName() { return cellarName; }
    public void cellarName(String v) { cellarName = v; }

    /** 记录酒窖归属（放置者）；已有归属时不覆盖。 */
    public void setOwner(Player p) {
        if (ownerId != null || p == null) return;
        ownerId = p.getUniqueId();
        ownerName = p.getName();
    }

    /** 该玩家是否为酒窖归属者（未记录归属时返回 false，由调用方决定回退策略）。 */
    public boolean isOwner(Player p) {
        return p != null && ownerId != null && ownerId.equals(p.getUniqueId());
    }

    /** 锁定酒窖配方（酿造开始时比例匹配）。 */
    public void setCellarRecipe(String key, int multiplier) {
        this.cellarRecipe = key;
        this.cellarMultiplier = multiplier;
    }

    /** 加入清水（稀释：不增加糖分，摊薄单位糖分；已含酒精时同步摊薄酒精度）。 */
    public void addWater(int n) {
        int before = units();
        waterUnits += n;
        int after = units();
        if (alcohol > 0 && before > 0 && after > before) {
            alcohol = alcohol * before / after;
        }
    }

    public List<Liquid> liquids() { return liquids; }
    public boolean hasAlcohol() { return alcohol > 0; }
    public boolean canAccept(int add) { return units() + add <= CAPACITY; }

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

    /** 投入液体（同组成同糖分合并，榨汁师并集；组成支持分数）。 */
    public void addLiquid(Map<String, Double> contents, int sugarPerUnit, int add, String player) {
        String key = JuicerRecipe.joinContentsFractional(contents) + "|" + sugarPerUnit;
        for (Liquid lq : liquids) {
            if (lq.key().equals(key)) {
                lq.units += add;
                if (player != null && !player.isEmpty()) lq.players.add(player);
                juiceUnits += add;
                return;
            }
        }
        Set<String> players = new LinkedHashSet<>();
        if (player != null && !player.isEmpty()) players.add(player);
        liquids.add(new Liquid(new LinkedHashMap<>(contents), sugarPerUnit, add, players));        juiceUnits += add;
    }

    /** 出酒消耗 1 单位（优先消耗果汁单位，其次清水）。 */
    public void drainUnit() {
        for (Liquid lq : liquids) {
            if (lq.units > 0) {
                lq.units--;
                juiceUnits--;
                return;
            }
        }
        if (waterUnits > 0) waterUnits--;
    }

    /** 全部液体合并后的组成（ref → 每单位数量之和；支持分数）。 */
    public Map<String, Double> contentsOfAll() {
        Map<String, Double> all = new LinkedHashMap<>();
        for (Liquid lq : liquids) {
            for (Map.Entry<String, Double> e : lq.contents().entrySet()) {
                all.merge(e.getKey(), e.getValue(), Double::sum);
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
        juiceUnits = 0;
        waterUnits = 0;
        yeast = null;
        alcohol = 0;
        elapsedMs = 0;
        durationMs = 0;
        nextGrowthAt = 0;
        phase = Phase.IDLE;
        placerName = null;
        placerId = null;
    }

    /** 彻底重置（多方块结构被破坏）：连归属者与酒窖名一并清除，原位重放不残留。 */
    public void clearIdentity() {
        clear();
        ownerName = null;
        ownerId = null;
        cellarName = null;
    }

    /** 酿造中报废。 */
    public void contaminate() {
        phase = Phase.CONTAMINATED;
        liquids.clear();
        juiceUnits = 0;
        waterUnits = 0;
        yeast = null;
        alcohol = 0;
    }

    // ===== 注册表与持久化 =====
    // 世界 → (打包坐标 → 状态)：热路径（管理器逐 tick 计时）零分配查询，
    // 规避 Location 哈希（CraftWorld#hashCode 走世界 UUID）的开销
    private static final Map<World, Map<Long, WineCellarState>> INDEX = new HashMap<>();

    /** 方块坐标打包为 long（x/z 26 位 + y 12 位）。 */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static WineCellarState get(Block b) {
        World w = b.getWorld();
        long k = key(b.getX(), b.getY(), b.getZ());
        Map<Long, WineCellarState> m = INDEX.get(w);
        if (m != null) {
            WineCellarState st = m.get(k);
            if (st != null) return st;
        }
        WineCellarState st = new WineCellarState();
        deserialize(st, BlockStorage.getLocationInfo(b.getLocation(), "wt-cellar-data"));
        INDEX.computeIfAbsent(w, x -> new HashMap<>()).put(k, st);
        return st;
    }

    public static void remove(Block b) {
        Map<Long, WineCellarState> m = INDEX.get(b.getWorld());
        if (m != null) m.remove(key(b.getX(), b.getY(), b.getZ()));
    }

    /**
     * 持久化当前状态（每次变更后调用）。
     * V2 格式：版本标记 + 头部 16 字段（到 cellarName）+ 各液体批次 4 字段（units|sugar|players|contents）。
     */
    public void save(Block b) {
        lastSaveMs = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("V2|")
          .append(phase.name()).append('|').append(mode.name()).append('|').append(elapsedMs)
          .append('|').append(durationMs).append('|').append(alcohol).append('|').append(nextGrowthAt)
          .append('|').append(yeast == null ? "" : yeast)
          .append('|').append(placerName == null ? "" : placerName)
          .append('|').append(placerId == null ? "" : placerId)
          .append('|').append(waterUnits)
          .append('|').append(cellarRecipe == null ? "" : cellarRecipe)
          .append('|').append(cellarMultiplier)
          .append('|').append(autoAge)
          .append('|').append(ownerName == null ? "" : ownerName)
          .append('|').append(ownerId == null ? "" : ownerId)
          .append('|').append(cellarName == null ? "" : cellarName);
        for (Liquid lq : liquids) {
            // 液体批次与头部一样用 '|' 分隔（整个串按 '|' 统一切分后按 4 字段一组解析；
            // 不能用 ';'——';' 不参与切分，首个批次的单位数会粘连到酒窖名字上）
            sb.append('|').append(lq.units).append('|').append(lq.sugarPerUnit).append('|')
              .append(String.join("~", lq.players)).append('|')
              .append(JuicerRecipe.joinContentsFractional(lq.contents));
        }
        BlockStorage.addBlockInfo(b, "wt-cellar-data", sb.toString());
    }

    private static void deserialize(WineCellarState st, String data) {
        if (data == null || data.isEmpty()) return;
        String[] parts = data.split("\\|", -1);
        try {
            if (parts.length >= 17 && parts[0].equals("V2")) {
                st.phase = Phase.valueOf(parts[1]);
                st.mode = Mode.valueOf(parts[2]);
                st.elapsedMs = Long.parseLong(parts[3]);
                st.durationMs = Long.parseLong(parts[4]);
                st.alcohol = Double.parseDouble(parts[5]);
                st.nextGrowthAt = Long.parseLong(parts[6]);
                st.yeast = parts[7].isEmpty() ? null : parts[7];
                st.placerName = parts[8].isEmpty() ? null : parts[8];
                st.placerId = parts[9].isEmpty() ? null : UUID.fromString(parts[9]);
                st.waterUnits = Integer.parseInt(parts[10]);
                st.cellarRecipe = parts[11].isEmpty() ? null : parts[11];
                st.cellarMultiplier = Integer.parseInt(parts[12]);
                st.autoAge = Boolean.parseBoolean(parts[13]);
                st.ownerName = parts[14].isEmpty() ? null : parts[14];
                st.ownerId = parts[15].isEmpty() ? null : UUID.fromString(parts[15]);
                // 旧版用 ';' 分隔液体段——';' 不参与切分，首个批次的单位数会粘连在名字后
                // （如「名字;2」）。检测到粘连时丢弃液体段（旧数据无法无损还原），保住命名信息
                String nameField = parts[16];
                boolean glued = nameField.indexOf(';') >= 0;
                if (glued) nameField = nameField.substring(0, nameField.indexOf(';'));
                st.cellarName = nameField.isEmpty() ? null : nameField;
                if (parts.length > 17 && !glued) parseLiquids(st, parts, 17);
            } else {
                parseLegacy(st, parts);
            }
        } catch (Exception ex) {
            WT.log("酒窖状态解析失败，已重置: " + ex);
            st.phase = Phase.IDLE;
            st.mode = Mode.BREW;
            st.liquids.clear();
            st.juiceUnits = 0;
            st.waterUnits = 0;
            st.alcohol = 0;
            st.yeast = null;
            st.elapsedMs = 0;
            st.durationMs = 0;
            st.nextGrowthAt = 0;
            st.cellarRecipe = null;
            st.cellarMultiplier = 1;
        }
    }

    /** 从 from 下标起按 4 字段一批解析液体批次，并重算果汁单位数。 */
    private static void parseLiquids(WineCellarState st, String[] parts, int from) {
        int i = from;
        while (i + 4 <= parts.length) {
            Map<String, Double> contents = JuicerRecipe.parseContentsFractional(parts[i + 3]);
            Set<String> players = new LinkedHashSet<>();
            for (String n : parts[i + 2].split("~")) {
                if (!n.isEmpty()) players.add(n);
            }
            st.liquids.add(new Liquid(contents, Integer.parseInt(parts[i + 1]),
                    Integer.parseInt(parts[i]), players));
            i += 4;
        }
        st.juiceUnits = 0;
        // juiceUnits 由液体批次重新累加；waterUnits 不能清零——新格式已在前面解析出
        for (Liquid lq : st.liquids) st.juiceUnits += lq.units;
    }

    /** 旧格式（无版本标记）兼容解析：9 字段头部，12 字段起含水单位/配方锁定，13 字段起含自动陈化。 */
    private static void parseLegacy(WineCellarState st, String[] parts) {
        if (parts.length < 9) return;
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
        if (parts.length >= 12) {
            st.waterUnits = Integer.parseInt(parts[9]);
            st.cellarRecipe = parts[10].isEmpty() ? null : parts[10];
            st.cellarMultiplier = Integer.parseInt(parts[11]);
            i = 12;
        }
        if (parts.length >= 13) {
            st.autoAge = Boolean.parseBoolean(parts[12]);
            i = 13;
        }
        // 旧版 ';' 粘连缺陷：液体段单位数粘连在最后一个头部字段上时，丢弃液体段保住头部
        if (i > 9 && i < parts.length && parts[i - 1].indexOf(';') >= 0) return;
        parseLiquids(st, parts, i);
    }
}
