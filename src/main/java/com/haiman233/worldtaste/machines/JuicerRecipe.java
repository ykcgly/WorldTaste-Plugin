package com.haiman233.worldtaste.machines;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.util.Colors;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 榨汁盆配方（juicer.yml 定义，{@link JuicerLoader} 加载）。
 *
 * <p>配方投入物为「多材料 + 数量」多集（juicer.yml 的 {@code ingredient: [- id:数量]}，
 * id 为原版材质名或粘液物品 id），榨汁时盆内内容物与某配方的投入多集<b>完全一致</b>即锁定
 * 该配方进入踩踏/铁砧进度；全部配方都不匹配时，若内容物均属于可混合列表（mix.recipes 中
 * 配方的投入物）则走混合榨汁。接取统一为玻璃瓶每次 1 份（水量 -1）、铁桶满盆一次接完，
 * 无 per-配方容器。</p>
 *
 * <p>头部 mix 段：可混合配方列表 + 混合榨汁进度 + 瓶/桶产物模板。混合产物 lore 标注内容物
 * （含数量），内容物数据写入产物 PDC（juice_contents / juice_bucket）持久保存，供后续功能
 * 读取。</p>
 */
public final class JuicerRecipe {

    /** 产物内容物数据键（瓶/桶装产物均写入，后续功能可读取）。 */
    public static final NamespacedKey KEY_ITEM_CONTENTS = new NamespacedKey(WT.plugin, "juice_contents");
    /** 桶装产物标记键（存在即禁止倒出）。 */
    public static final NamespacedKey KEY_ITEM_BUCKET = new NamespacedKey(WT.plugin, "juice_bucket");
    /** 产物糖分键（瓶装=单份糖分，桶装=整批总糖分；供后续玩法读取）。 */
    public static final NamespacedKey KEY_ITEM_SUGAR = new NamespacedKey(WT.plugin, "juice_sugar");
    /** 产物榨汁师键（逗号分隔玩家名；酒窖投入时保留榨汁者数据）。 */
    public static final NamespacedKey KEY_ITEM_PLAYERS = new NamespacedKey(WT.plugin, "juice_players");
    /** 陈酿果酒标记键（存在即禁止再次投入酒窖）。 */
    public static final NamespacedKey KEY_ITEM_WINE = new NamespacedKey(WT.plugin, "juice_wine");
    /** 陈酿果酒酒精度键（DOUBLE，每瓶一致）。 */
    public static final NamespacedKey KEY_ITEM_ALCOHOL = new NamespacedKey(WT.plugin, "juice_alcohol");

    /** 酒曲加成表（Slimefun id → 最终酒精度加成；不在表内 = 非酒曲）。 */
    private static final Map<String, Integer> YEAST_BONUS = Map.of(
            "YEAST_1", 0, "WT_JIUQU", 3, "YEAST_2", 6, "YEAST_3", 15, "YEAST_4", 30);

    /** 材料糖分表（ref → 糖分，sugar.yml 载入；未列出按 0）。 */
    private static final Map<String, Integer> SUGAR = new HashMap<>();

    // ===== 全局设置（juicer.yml 头部，JuicerLoader 载入）=====
    /** 是否在踩踏产物 lore 末尾追加榨汁师（参与踩踏玩家名）。 */
    public static boolean playerNames = true;
    /** 榨汁信息 lore 行格式（支持 & 颜色代码；占位符 %playername% 玩家名、%time% 榨好时间）。 */
    public static String playerNamesFormat =
            "&8[&6榨汁盆&8] &7榨汁师: &f%playername% &7(&b%time%&7)";
    /** 是否启用铁砧损耗（false = 铁砧砸落永不损坏）。 */
    public static boolean anvilDamage = false;
    /** 铁砧损耗概率（1~10，实际概率 = 数值/10，每次砸落判定一次）。 */
    public static int anvilDamageChance = 3;
    /** 糖分→酒精度转化比率（酒窖酿造：最终酒精度 = 总糖分 × 比率 + 酒曲加成）。 */
    public static double sugarAlcoholRatio = 0.5;
    /** 陈化每游戏日酒精度增长百分比下限（实际取 min~max 随机）。 */
    public static double agingGrowthMin = 0.05;
    /** 陈化每游戏日酒精度增长百分比上限。 */
    public static double agingGrowthMax = 0.15;
    /** 陈酿果酒 lore 信息行格式（%players% 榨汁玩家、%contents% 组成果汁、%alcohol% 酒精度）。 */
    public static String wineLoreFormat =
            "&8[&6酒窖&8] &7榨汁师: &f%players% &7| &b%contents% &7| &c酒精度: &e%alcohol%°";

    private static final java.text.SimpleDateFormat TIME_FORMAT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");

    private static final Map<String, JuicerRecipe> RECIPES = new LinkedHashMap<>();

    /** 可混合配置（juicer.yml 头部 mix 段）。 */
    public static final class MixCfg {
        /** 可混合物品 ref 集合（mix.recipes 材料列表，按配置顺序）。 */
        public final Set<String> mixableRefs = new LinkedHashSet<>();
        /** 混合榨汁所需踩踏进度（player 型）。 */
        public int progress = 10;
        /** 瓶装混合产物模板。 */
        public ItemStack bottleTemplate;
        /** 桶装混合产物模板（禁止倒出）。 */
        public ItemStack bucketTemplate;
    }

    /** 混合配置；juicer.yml 未定义 mix 段时为 null（无混合玩法）。 */
    public static MixCfg mix;

    /** 比例匹配结果：内容物为某配方投入多集的 multiplier 倍。 */
    public static final class MatchResult {
        public final JuicerRecipe recipe;
        public final int multiplier;

        MatchResult(JuicerRecipe recipe, int multiplier) {
            this.recipe = recipe;
            this.multiplier = multiplier;
        }
    }

    public final String key;
    /** 投入物多集：ref(mc:XXX/sf:XXX) → 数量。 */
    public final Map<String, Integer> ingredientRefs;
    /** 投入物总数量（容量校验与匹配预筛用）。 */
    public final int totalInput;
    /** 投入物展示堆（第一个投入物，榨汁中展示实体用）。 */
    public final ItemStack ingredientDisplay;
    /** true=允许玩家踩踏榨汁（每次跳跃 +1）。混合榨汁固定为玩家型。 */
    public final boolean playerType;
    /** true=允许铁砧砸击榨汁（每次砸落 +4）。 */
    public final boolean anvilType;
    /** 总进度（player 按次计 1，anvil 按次计 4）。 */
    public final int progress;
    /** 榨好后的接取份数（= 榨汁盆含水等级，1~3；瓶子每次接 1 份，桶满时一次接完）。 */
    public final int yield;
    /** 每份产物模板（含数量）。 */
    public final ItemStack result;
    /** 桶装形态模板（juicer.yml 可选 bucket 段；缺省由 result 自动生成「桶装<名>」）。 */
    public ItemStack bucketForm;

    public JuicerRecipe(String key, Map<String, Integer> ingredientRefs, ItemStack ingredientDisplay,
                        boolean playerType, boolean anvilType, int progress, int yield, ItemStack result) {
        this.key = key;
        this.ingredientRefs = ingredientRefs;
        this.totalInput = sum(ingredientRefs);
        this.ingredientDisplay = ingredientDisplay;
        this.playerType = playerType;
        this.anvilType = anvilType;
        this.progress = progress;
        this.yield = yield;
        this.result = result;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    public static void register(JuicerRecipe r) {
        RECIPES.put(r.key, r);
    }

    public static JuicerRecipe byKey(String key) {
        return key == null ? null : RECIPES.get(key);
    }

    public static int size() {
        return RECIPES.size();
    }

    /** 全部配方（注册顺序，供配方展示菜单翻页）。 */
    public static java.util.List<JuicerRecipe> all() {
        return new ArrayList<>(RECIPES.values());
    }

    /**
     * 比例匹配：内容物多集为某配方投入多集的整数倍（k≥1，k=1 即完全一致）。
     * 多个候选取倍率最小者（最特异配方优先）。开始榨汁时据此锁定配方，
     * 接取份数 = k × yield。
     */
    public static MatchResult matchProportional(Map<String, Integer> contents, int total) {
        MatchResult best = null;
        for (JuicerRecipe r : RECIPES.values()) {
            if (r.totalInput == 0 || total % r.totalInput != 0) continue;
            if (r.ingredientRefs.size() != contents.size()) continue;
            int k = total / r.totalInput;
            boolean ok = true;
            for (Map.Entry<String, Integer> e : contents.entrySet()) {
                if (e.getValue() != r.ingredientRefs.getOrDefault(e.getKey(), 0) * k) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || k < best.multiplier)) best = new MatchResult(r, k);
        }
        return best;
    }

    /** 子集匹配：内容物是否恰为某配方投入多集的子集（多材料配方逐步投入过程中的未完成态）。 */
    public static boolean isSubset(Map<String, Integer> contents, int total) {
        for (JuicerRecipe r : RECIPES.values()) {
            if (r.totalInput < total || r.ingredientRefs.size() < contents.size()) continue;
            if (subsetOf(contents, r.ingredientRefs)) return true;
        }
        return false;
    }

    private static boolean subsetOf(Map<String, Integer> sub, Map<String, Integer> sup) {
        for (Map.Entry<String, Integer> e : sub.entrySet()) {
            if (sup.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** 设置材料糖分（SugarLoader 载入）。 */
    public static void setSugar(String ref, int sugar) {
        if (ref != null && sugar > 0) SUGAR.put(ref, sugar);
    }

    /** 材料糖分（未列出按 0）。 */
    public static int sugarOf(String ref) {
        return SUGAR.getOrDefault(ref, 0);
    }

    /** 内容物多集的总糖分 = Σ 材料糖分 × 数量。 */
    public static int totalSugar(Map<String, Integer> contents) {
        int total = 0;
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            total += sugarOf(e.getKey()) * e.getValue();
        }
        return total;
    }

    /** 投入物是否属于可混合列表。 */
    public static boolean isMixable(ItemStack item) {
        if (mix == null || item == null || item.getType().isAir()) return false;
        return mix.mixableRefs.contains(refOf(item));
    }

    /** 内容物是否全部可混合。 */
    public static boolean allMixable(Map<String, Integer> contents) {
        if (mix == null) return false;
        for (String ref : contents.keySet()) {
            if (!mix.mixableRefs.contains(ref)) return false;
        }
        return true;
    }

    /** 酒曲加成（非酒曲返回 -1）。 */
    public static int yeastBonus(String yeastId) {
        return yeastId == null ? -1 : YEAST_BONUS.getOrDefault(yeastId, -1);
    }

    /** 内容物多集是否合法（比例匹配 / 可混合 / 某配方子集，任一即可进入盆中）。 */
    public static boolean validContents(Map<String, Integer> contents, int total) {
        return matchProportional(contents, total) != null || allMixable(contents) || isSubset(contents, total);
    }

    /**
     * 构建每份产物：模板克隆；玩家踩踏配方在 player-names 开启时于 lore 末尾按
     * player-names-format 追加榨汁信息行。
     *
     * <p>内容物 refs 必须写入 PDC（{@link #KEY_ITEM_CONTENTS}，单份 = 配方投入多集）——
     * 酒窖 {@code CellarMenu} 靠它识别果汁并换算液位/糖分，缺失则该瓶果汁投入后不被吸收。
     * 与 {@link #buildBucketResult} 保持一致（两者均为配方匹配的产物，桶装取的是整批）。</p>
     */
    public ItemStack buildResult(Set<String> players, long completedAt, int sugar) {
        ItemStack out = result.clone();
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            appendJuiceInfo(meta, players, completedAt);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContents(ingredientRefs));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            out.setItemMeta(meta);
        }
        return out;
    }

    /**
     * 构建桶装形态产物（满盆用桶一次接取全部）：统一水桶材质，名称为「桶装<产物名>」，
     * lore 与瓶装一致（含榨汁信息行），内容物 refs 写入 PDC 并带 juice_bucket 标记
     * （禁止倒出）。juicer.yml 配方可用可选 bucket 段完全自定义外观。
     */
    public ItemStack buildBucketResult(Set<String> players, long completedAt, int sugar) {
        boolean custom = bucketForm != null;
        ItemStack out = (custom ? bucketForm : result).clone();
        String name = null;
        if (!custom) {
            // 先取原名（含颜色码），再统一切换为水桶材质
            ItemMeta origin = out.getItemMeta();
            if (origin != null) name = origin.getDisplayName();
            out.setType(Material.WATER_BUCKET);
        }
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            if (!custom) {
                meta.setDisplayName(bucketName(name));
            }
            appendJuiceInfo(meta, players, completedAt);
            meta.getPersistentDataContainer().set(KEY_ITEM_BUCKET, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContents(ingredientRefs));
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 榨汁信息 lore 行（player-names 开启时）：按格式渲染 %playername% / %time% 占位符。 */
    private static void appendJuiceInfo(ItemMeta meta, Set<String> players, long completedAt) {
        if (!playerNames || players == null || players.isEmpty()) return;
        String fmt = playerNamesFormat == null || playerNamesFormat.isEmpty()
                ? "&8[&6榨汁盆&8] &7榨汁师: &f%playername%" : playerNamesFormat;
        StringBuilder names = new StringBuilder();
        for (String n : players) {
            if (names.length() > 0) names.append('、');
            names.append(n);
        }
        String time = completedAt > 0 ? TIME_FORMAT.format(new java.util.Date(completedAt)) : "--";
        String line = fmt.replace("%playername%", names.toString()).replace("%time%", time);
        List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
        lore.add(Colors.c(line));
        meta.setLore(lore);
    }

    /** 在显示名的颜色码之后插入「桶装」前缀（§6§l苹果汁 → §6§l桶装苹果汁）。 */
    private static String bucketName(String legacyName) {
        if (legacyName == null) return "桶装";
        int i = 0;
        while (i + 1 < legacyName.length() && legacyName.charAt(i) == '§') i += 2;
        return legacyName.substring(0, i) + "桶装" + legacyName.substring(i);
    }

    /**
     * 构建混合产物（瓶装 = 1 份；桶装 = 整批，桶内每单位组成相同）。内容物按<b>每单位
     * 组成</b>记录：批次内容物 ÷ 份数（支持分数，如 2/3），瓶/桶倒入酒窖时按单位数还原
     * 整批材料，不会凭空增多/减少；lore 内容行整数显示 ×N、分数显示百分比；内容物写入
     * 产物 PDC（juice_contents），桶装额外带 juice_bucket 标记（交互时禁止倒出）。
     */
    public static ItemStack buildMixProduct(boolean bucket, Map<String, Integer> contents,
                                            Set<String> players, long completedAt, int sugar, int doses) {
        ItemStack template = bucket ? mix.bucketTemplate : mix.bottleTemplate;
        ItemStack out = template.clone();
        // 每单位组成 = 批次内容物 ÷ 份数
        int den = Math.max(1, doses);
        Map<String, Double> perUnit = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            perUnit.put(e.getKey(), (double) e.getValue() / den);
        }
        // lore 展示：桶装 = 整批数量（×2）；瓶装 = 每份组成（分数按百分比）
        Map<String, Double> display = new LinkedHashMap<>();
        if (bucket) {
            for (Map.Entry<String, Integer> e : contents.entrySet()) {
                display.put(e.getKey(), (double) e.getValue());
            }
        } else {
            display.putAll(perUnit);
        }
        ItemMeta meta = out.getItemMeta();
        if (meta != null) {
            appendJuiceInfo(meta, players, completedAt);
            if (players != null && !players.isEmpty()) {
                meta.getPersistentDataContainer().set(KEY_ITEM_PLAYERS, PersistentDataType.STRING,
                        String.join(",", players));
            }
            meta.getPersistentDataContainer().set(KEY_ITEM_SUGAR, PersistentDataType.INTEGER, sugar);
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            TextComponent.Builder line = Component.text().append(Component.text("内容: ", NamedTextColor.GRAY));
            boolean first = true;
            for (Map.Entry<String, Double> e : display.entrySet()) {
                if (!first) line.append(Component.text("、", NamedTextColor.GRAY));
                first = false;
                line.append(nameComponent(e.getKey()));
                line.append(Component.text(quantityText(e.getValue()), NamedTextColor.GRAY));
            }
            lore.add(line.build());
            meta.lore(lore);
            meta.getPersistentDataContainer().set(KEY_ITEM_CONTENTS, PersistentDataType.STRING,
                    joinContentsFractional(perUnit));
            if (bucket) meta.getPersistentDataContainer().set(KEY_ITEM_BUCKET, PersistentDataType.BYTE, (byte) 1);
            out.setItemMeta(meta);
        }
        return out;
    }

    /** 内容物多集序列化（ref:数量 逗号分隔；盆状态持久化与产物 PDC 共用此格式）。 */
    public static String joinContents(Map<String, Integer> contents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** 内容物序列化（支持分数）：整数写 n，非整数写约分后的 a/b（如 2/3）。 */
    public static String joinContentsFractional(Map<String, Double> contents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : contents.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(formatFraction(e.getValue()));
        }
        return sb.toString();
    }

    /** 反序列化内容物多集（兼容无数量的旧格式，按 1 计）。 */
    public static Map<String, Integer> parseContents(String data) {
        Map<String, Integer> contents = new HashMap<>();
        if (data == null || data.isEmpty()) return contents;
        for (String entry : data.split(",")) {
            if (entry.isEmpty()) continue;
            int idx = entry.lastIndexOf(':');
            if (idx <= 0) {
                contents.merge(entry, 1, Integer::sum);
                continue;
            }
            String ref = entry.substring(0, idx);
            int count;
            try {
                count = Integer.parseInt(entry.substring(idx + 1));
            } catch (NumberFormatException ex) {
                count = 1;
            }
            if (count > 0) contents.merge(ref, count, Integer::sum);
        }
        return contents;
    }

    /** 反序列化内容物（支持整数 n 与分数 a/b，用于酒窖吸收与状态解析）。 */
    public static Map<String, Double> parseContentsFractional(String data) {
        Map<String, Double> contents = new LinkedHashMap<>();
        if (data == null || data.isEmpty()) return contents;
        for (String entry : data.split(",")) {
            if (entry.isEmpty()) continue;
            int idx = entry.lastIndexOf(':');
            if (idx <= 0) {
                contents.merge(entry, 1.0, Double::sum);
                continue;
            }
            String ref = entry.substring(0, idx);
            String v = entry.substring(idx + 1);
            try {
                double val;
                int slash = v.indexOf('/');
                if (slash > 0) {
                    val = Double.parseDouble(v.substring(0, slash))
                            / Double.parseDouble(v.substring(slash + 1));
                } else {
                    val = Double.parseDouble(v);
                }
                if (val > 0) contents.merge(ref, val, Double::sum);
            } catch (NumberFormatException ignored) {
                contents.merge(ref, 1.0, Double::sum);
            }
        }
        return contents;
    }

    /** 数量格式化（PDC/液位）：整数 → "2"，非整数 → 约分分数 "2/3"。 */
    public static String formatFraction(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        for (int den = 2; den <= 1000; den++) {
            double num = v * den;
            if (Math.abs(num - Math.rint(num)) < 1e-6) {
                long n = Math.round(num);
                long g = gcd(Math.abs(n), den);
                return (n / g) + "/" + (den / g);
            }
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 数量格式化（lore 展示）：整数 → "×2"，非整数 → 百分比 "×66.7%"（末位 0 舍去）。 */
    public static String quantityText(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return "×" + (long) Math.rint(v);
        double pct = v * 100;
        if (Math.abs(pct - Math.rint(pct)) < 0.05) return "×" + (long) Math.rint(pct) + "%";
        return "×" + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%";
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** 内容物 ref（mc:APPLE / sf:ID）的本地化名称组件：原版用翻译键，粘液物品用其显示名。 */
    public static Component nameComponent(String ref) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf != null) return sf.getItem().displayName();
            return Component.text(ref.substring(3), NamedTextColor.WHITE);
        }
        Material m = Material.matchMaterial(ref.substring(3));
        if (m != null) return Component.translatable(m.translationKey());
        return Component.text(ref, NamedTextColor.WHITE);
    }

    /** 物品堆的内容物 ref。 */
    public static String refOf(ItemStack item) {
        SlimefunItem sf = SlimefunItem.getByItem(item);
        return sf != null ? "sf:" + sf.getId() : "mc:" + item.getType().name();
    }

    /** 内容物 ref 对应的展示物品堆（榨汁中展示实体用）。 */
    public static ItemStack refToItem(String ref) {
        if (ref.startsWith("sf:")) {
            SlimefunItem sf = SlimefunItem.getById(ref.substring(3));
            if (sf != null) return sf.getItem().clone();
            return new ItemStack(Material.BARRIER);
        }
        Material m = Material.matchMaterial(ref.substring(3));
        return new ItemStack(m != null ? m : Material.BARRIER);
    }
}
