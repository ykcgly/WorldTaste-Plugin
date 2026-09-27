package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 加载期「物品未找到」的折叠上报。
 *
 * <p>内容 YAML 里有上万处 {@code material_type: slimefun} 引用（大量指向美食家 {@code GN_*}、
 * {@code YEAST} 等其它附属物品）。目标服务器未安装对应附属时，旧实现会为每一处引用打印一行
 * 「未找到粘液物品」警告，一次启动刷出数千行日志。</p>
 *
 * <p>本类把未命中按 id 去重计数（普通物品静默），加载结束时由 {@link #report()} 汇总成一行；
 * 仅当缺失的是 {@link #SPECIAL} 中的「特殊物品」——即 Java 代码硬引用、缺失即对应功能不可用的
 * 物品——才立即单条告警。</p>
 *
 * <p>仅加载期使用：{@link Setup#loadAll()} 开头 {@link #reset()}、结尾 {@link #report()}。</p>
 */
public final class MissingItems {

    private MissingItems() {}

    /**
     * 特殊物品 id → 缺失影响说明。这些 id 被 Java 侧硬引用（机器/交互/酿造核心），
     * 缺失不只是「配方变成占位方块」，而是对应功能整体不可用，故需要立即告警。
     */
    private static final Map<String, String> SPECIAL = Map.of(
            "WT_ZHAPEN", "榨汁盆（酿造工艺核心机器）",
            "WT_JIUJIAO", "酒窖管理器（酿造工艺核心机器）",
            "WT_WENDU", "温度控制器（酒窖温控）",
            "WT_GUOZHA", "果渣（榨汁产物）",
            "WT_SWEET_PAPER", "甜度试纸",
            "WT_WINE", "陈酿果酒（酒窖产物）",
            "WT_JIUQU", "酒曲（酿造加成）");

    /** 汇总展示的示例 id 上限（超出部分以「……等」收尾，避免汇总本身又刷屏）。 */
    private static final int MAX_EXAMPLES = 30;

    /** 未命中引用：id(大写) -> 出现次数。 */
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    /** 特殊物品已告警集合（同一物品只告警一次）。 */
    private static final Set<String> WARNED_SPECIAL = new HashSet<>();

    /** 加载开始时清空上一轮状态（{@link Setup#loadAll()} 调用）。 */
    public static void reset() {
        COUNTS.clear();
        WARNED_SPECIAL.clear();
    }

    /**
     * 记录一次「物品未找到」。特殊物品立即告警（每个 id 只告警一次）；普通物品静默计数，
     * 由 {@link #report()} 在加载结束时汇总为一行。
     *
     * <p>{@code id} 也可以带前缀区分来源（如 {@code "材质 XXX"} 表示未知原版材质），
     * 仅用于汇总展示，特殊物品匹配按原样大写比较。</p>
     */
    public static void record(String id) {
        if (id == null || id.isEmpty()) return;
        String key = id.toUpperCase(Locale.ROOT);
        String what = SPECIAL.get(key);
        if (what != null) {
            if (WARNED_SPECIAL.add(key)) {
                WT.log("特殊物品缺失: " + key + "（" + what + "），相关功能将不可用！");
            }
            return;
        }
        COUNTS.merge(key, 1, Integer::sum);
    }

    /** 加载结束汇总：一行说明 + 最多 {@value #MAX_EXAMPLES} 个示例（带出现次数）。 */
    public static void report() {
        if (COUNTS.isEmpty()) return;
        int total = 0;
        for (int n : COUNTS.values()) total += n;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            if (shown >= MAX_EXAMPLES) {
                sb.append(" ……等共 ").append(COUNTS.size()).append(" 种");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey()).append('×').append(e.getValue());
            shown++;
        }
        WT.log("有 " + COUNTS.size() + " 种被引用的物品未找到（共 " + total
                + " 处引用，对应配方/展示已用占位方块代替）：" + sb);
        COUNTS.clear();
    }
}
