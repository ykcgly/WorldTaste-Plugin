package com.haiman233.worldtaste.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒窖配方（cellar.yml 定义，CellarLoader 加载）。
 *
 * <p>匹配为<b>按材料份数</b>：每种果汁按其单位数贡献「份数」（含某原料的每单位果汁记该
 * 原料 1 份），液体中各原料的份数与配方 ingredient 的数量成<b>整数倍比例</b>（k 倍）时匹配，
 * 酿造产出 k × 单位产物。产物可为酒水（aging=true，可进入陈化模式增长酒精度）
 * 或副产物（aging=false，无酒精度）。</p>
 */
public final class CellarRecipe {

    private static final Map<String, CellarRecipe> RECIPES = new LinkedHashMap<>();

    /** 比例匹配结果。 */
    public static final class MatchResult {
        public final CellarRecipe recipe;
        public final int multiplier;

        MatchResult(CellarRecipe recipe, int multiplier) {
            this.recipe = recipe;
            this.multiplier = multiplier;
        }
    }

    public final String key;
    /** 投入物：ref(mc:XXX/sf:XXX) → 该原料果汁的份数。 */
    public final Map<String, Integer> ingredientRefs;
    /** 投入总单位数。 */
    public final int totalInput;
    /** 是否允许陈酿（true = 产物含酒精，可陈化增长；false = 副产物，无酒精度）。 */
    public final boolean aging;
    /** 单位产物模板。 */
    public final ItemStack result;

    public CellarRecipe(String key, Map<String, Integer> ingredientRefs, boolean aging, ItemStack result) {
        this.key = key;
        this.ingredientRefs = ingredientRefs;
        this.totalInput = sum(ingredientRefs);
        this.aging = aging;
        this.result = result;
    }

    private static int sum(Map<String, Integer> refs) {
        int t = 0;
        for (int n : refs.values()) t += n;
        return t;
    }

    public static void register(CellarRecipe r) {
        RECIPES.put(r.key, r);
    }

    public static CellarRecipe byKey(String key) {
        return key == null ? null : RECIPES.get(key);
    }

    public static List<CellarRecipe> all() {
        return new ArrayList<>(RECIPES.values());
    }

    /**
     * 按材料份数匹配：portions 为各原料的果汁份数（ref → 份数，由液体单位数按所含原料汇总）。
     * 各原料份数与配方 ingredient 数量成整数倍（k≥1）比例时匹配；多个候选取倍率最小者。
     * 清水不参与（无原料 ref）。
     */
    public static MatchResult match(Map<String, Integer> portions) {
        MatchResult best = null;
        int total = 0;
        for (int n : portions.values()) total += n;
        for (CellarRecipe r : RECIPES.values()) {
            if (r.totalInput == 0 || total % r.totalInput != 0) continue;
            int k = total / r.totalInput;
            boolean ok = true;
            for (Map.Entry<String, Integer> e : portions.entrySet()) {
                if (e.getValue() != r.ingredientRefs.getOrDefault(e.getKey(), 0) * k) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || k < best.multiplier)) best = new MatchResult(r, k);
        }
        return best;
    }
}
