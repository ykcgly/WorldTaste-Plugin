package com.haiman233.worldtaste.util;

import com.haiman233.worldtaste.WT;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 语言文件管理器（i18n）。
 *
 * <p>语言文件位于 {@code plugins/WorldTaste/lang/<语言码>.yml}，首次启动时由 jar 内的
 * {@code lang/<语言码>.yml} 释放。语言码由 {@code config.yml} 的 {@code language} 指定。</p>
 *
 * <p><b>查找顺序（逐级回退，任一级命中即返回）</b>：</p>
 * <ol>
 *   <li>当前语言文件；</li>
 *   <li>默认语言 {@link #DEFAULT}（zh_CN）——保证任何语言都不会出现空白文本；</li>
 *   <li>空值/空白字符串视为「未翻译」，继续向下回退（译者可留空占位）。</li>
 * </ol>
 * <p>内容层的第 4 级回退由调用方承担：{@link #of(String, String)} /
 * {@link #ofList(String, List)} 在两级语言都缺失时返回 {@code content/*.yml} 的内联文本，
 * 于是「语言文件缺键 → 显示 content 里原本的中文」而非报错。</p>
 *
 * <p><b>键名扁平化</b>：语言文件本身按普通 YAML 嵌套书写（好读好改），加载时一次性压平成
 * {@code a.b.c} 形式存入 Map。这样运行期只做一次哈希查找，也避开了 Bukkit
 * {@code MemorySection} 把 {@code .} 当路径分隔符的麻烦（扁平点号键在 Bukkit 里查不到）。</p>
 *
 * <p><b>占位符</b>：{@code {0}}、{@code {1}}…按参数顺序替换，避免 {@code String.format} 与
 * 文本里本来就有的 {@code %} 打架。</p>
 */
public final class Lang {

    private Lang() {}

    /** 默认语言：内容与 Java 侧全部键都在此文件中，作为最终兜底。 */
    public static final String DEFAULT = "zh_CN";

    /**
     * jar 内置语言：启动时一律释放到 {@code plugins/WorldTaste/lang/}，
     * 让服主能直接看到有哪些语言可用、并可复制改名做自己的翻译。
     */
    private static final String[] BUILTIN = { "zh_CN", "en_US" };

    /** 语言文件所在目录（插件数据目录下）。 */
    private static final String DIR = "lang";

    /** 当前语言码。 */
    private static String code = DEFAULT;

    /** 当前语言扁平表：a.b.c -> String | List&lt;String&gt;。 */
    private static Map<String, Object> current = new HashMap<>();

    /** 默认语言（zh_CN）扁平表，作为回退。 */
    private static Map<String, Object> fallback = new HashMap<>();

    /** 本次加载期内缺失的键（折叠上报，避免逐条刷屏）。 */
    private static final Map<String, Integer> MISSING = new LinkedHashMap<>();

    /**
     * 加载语言文件。必须在任何内容加载之前调用（{@code Setup.loadAll} 中紧随
     * {@code WTConfig.load()} 之后）——分组名、配方类型名、菜单标题都依赖它。
     *
     * @param wanted config.yml 的 {@code language} 值；为 null/空/文件名非法时回退 {@link #DEFAULT}
     */
    public static void load(String wanted) {
        code = normalize(wanted);
        exportBuiltins();
        fallback = flatten(open(DEFAULT, true));
        if (fallback.isEmpty()) {
            WT.log("[Lang] 内置 " + DEFAULT + " 语言文件缺失或为空，内容文本将回退 content/*.yml 内联值");
        }
        if (DEFAULT.equals(code)) {
            current = fallback;
        } else {
            Map<String, Object> m = flatten(open(code, true));
            if (m.isEmpty()) {
                WT.log("[Lang] 语言文件 lang/" + code + ".yml 不存在或为空，已回退默认语言 " + DEFAULT);
                code = DEFAULT;
                current = fallback;
            } else {
                current = m;
            }
        }
        MISSING.clear();
        if (WT.plugin != null) {
            WT.plugin.getLogger().info("语言: " + code + "（键 " + current.size() + " 条"
                    + (DEFAULT.equals(code) ? "" : "，回退 " + DEFAULT + " 键 " + fallback.size() + " 条") + "）");
        }
    }

    /** 按当前 config 语言重新加载（供未来热重载入口调用）。 */
    public static void reload() {
        load(com.haiman233.worldtaste.load.WTConfig.language);
    }

    /** 当前语言码（如 {@code zh_CN}）。 */
    public static String code() {
        return code;
    }

    /**
     * 取文本（原始，不做颜色转换）：当前语言 → 默认语言 → 键名回显。
     *
     * <p>键名回显是刻意设计：漏翻时界面会出现 {@code ui.cellar.not_idle} 这样的字样，
     * 一眼能看出是缺键而不是「文案变空白」，便于自测与反馈。</p>
     */
    public static String get(String key) {
        String v = lookupString(key);
        if (v != null) return v;
        miss(key);
        return key;
    }

    /** 取文本并替换 {@code {0}/{1}…} 占位符（原始，不做颜色转换）。 */
    public static String get(String key, Object... args) {
        return fmt(get(key), args);
    }

    /**
     * 取文本并转换颜色码（{@code &a} / {@code &#RRGGBB} / 已有的 {@code §}），
     * 供聊天栏、动作栏、GUI 标题等直接展示的场合使用。
     */
    public static String c(String key, Object... args) {
        return Colors.c(fmt(get(key), args));
    }

    /**
     * 内容层取值：语言文件命中则用语言文件，否则用 content/*.yml 里的内联文本。
     *
     * <p>返回值为原始文本（颜色转换仍由调用方按原逻辑处理，例如 {@code Read.item}
     * 统一走 {@code Colors.c}）。{@code inline} 为 null 且语言文件也缺键时返回 null。</p>
     */
    public static String of(String key, String inline) {
        String v = lookupString(key);
        return v != null ? v : inline;
    }

    /** 内容层多行取值（lore）。同 {@link #of(String, String)} 的语义。 */
    public static List<String> ofList(String key, List<String> inline) {
        List<String> v = lookupList(key);
        return v != null ? v : inline;
    }

    /** 取多行文本；两级语言都缺键时返回空列表。 */
    public static List<String> list(String key) {
        List<String> v = lookupList(key);
        if (v != null) return v;
        miss(key);
        return Collections.emptyList();
    }

    /** 键是否存在（含默认语言回退）。 */
    public static boolean has(String key) {
        return lookupString(key) != null || lookupList(key) != null;
    }

    /**
     * 缺失键汇总（{@code Setup.loadAll} 末尾调用）：一次性输出条数与前若干个键名。
     * 正常情况应为 0；非 0 说明语言文件与代码/内容不同步。
     */
    public static void report() {
        if (MISSING.isEmpty()) return;
        int total = 0;
        for (int n : MISSING.values()) total += n;
        List<String> keys = new ArrayList<>(MISSING.keySet());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size() && i < 12; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys.get(i));
        }
        WT.log("[Lang] 语言文件缺失 " + total + " 处文本（" + MISSING.size() + " 个键）：" + sb
                + (MISSING.size() > 12 ? ", …" : ""));
    }

    // ---------------------------------------------------------------- 内部实现

    private static String lookupString(String key) {
        if (key == null) return null;
        Object v = current.get(key);
        if (v instanceof String s && !s.isEmpty()) return s;
        v = fallback.get(key);
        if (v instanceof String s && !s.isEmpty()) return s;
        return null;
    }

    private static List<String> lookupList(String key) {
        if (key == null) return null;
        Object v = current.get(key);
        if (v instanceof List<?> l && !l.isEmpty()) return asList(l);
        v = fallback.get(key);
        if (v instanceof List<?> l && !l.isEmpty()) return asList(l);
        return null;
    }

    private static List<String> asList(List<?> l) {
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) out.add(o == null ? "" : String.valueOf(o));
        return out;
    }

    private static String fmt(String text, Object... args) {
        if (text == null || args == null || args.length == 0) return text;
        String out = text;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    private static void miss(String key) {
        MISSING.merge(key, 1, Integer::sum);
    }

    /**
     * 语言码规范化：只允许字母/数字/下划线/连字符（防路径穿越，
     * {@code language: ../../config} 之类不能读到任意文件），非法值回退默认语言。
     */
    private static String normalize(String wanted) {
        if (wanted == null) return DEFAULT;
        String s = wanted.trim();
        if (s.isEmpty()) return DEFAULT;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                WT.log("[Lang] config.yml 的 language 值 \"" + wanted + "\" 含非法字符，已回退 " + DEFAULT);
                return DEFAULT;
            }
        }
        // 文件名校验统一用「原名」，避免 Linux 区分大小写导致 zh_cn/zh_CN 读不到
        return s;
    }

    /**
     * 把 jar 内置语言释放到 {@code plugins/WorldTaste/lang/}。
     * 已存在的文件**不覆盖**——服主/译者改过的内容优先。
     */
    private static void exportBuiltins() {
        File dir = new File(WT.plugin.getDataFolder(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            WT.log("[Lang] 无法创建语言目录 " + dir.getPath());
            return;
        }
        for (String locale : BUILTIN) {
            File f = new File(dir, locale + ".yml");
            if (f.exists()) continue;
            try {
                WT.plugin.saveResource(DIR + "/" + locale + ".yml", false);
            } catch (Exception ignored) {
                // 该语言没打进 jar：跳过即可（选中时 open 会再尝试 jar 直读并回退默认语言）
            }
        }
    }

    /**
     * 打开语言文件：优先磁盘（管理员可改），缺失时从 jar 释放；再缺失则直接读 jar 内资源
     * （只读兜底，防止 saveResource 失败时彻底没有文本）。
     */
    private static YamlConfiguration open(String locale, boolean export) {
        File dir = new File(WT.plugin.getDataFolder(), DIR);
        File file = new File(dir, locale + ".yml");
        if (!file.exists() && export) {
            try {
                if (!dir.exists()) dir.mkdirs();
                WT.plugin.saveResource(DIR + "/" + locale + ".yml", false);
            } catch (Exception ignored) {
                // jar 内没有该语言：不视为错误，继续走 jar 直读/回退
            }
        }
        if (file.exists()) {
            try {
                return YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                WT.log("[Lang] 读取 " + file.getName() + " 失败，改用 jar 内置版本: " + e);
            }
        }
        try (InputStream in = WT.plugin.getResource(DIR + "/" + locale + ".yml")) {
            if (in == null) return null;
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            }
        } catch (Exception e) {
            WT.log("[Lang] 读取 jar 内 lang/" + locale + ".yml 失败: " + e);
            return null;
        }
    }

    /** 嵌套 YAML 压平为 {@code a.b.c -> String|List<String>}。 */
    private static Map<String, Object> flatten(YamlConfiguration y) {
        Map<String, Object> out = new HashMap<>();
        if (y == null) return out;
        for (String key : y.getKeys(true)) {
            Object v = y.get(key);
            if (v instanceof String s) {
                out.put(key, s);
            } else if (v instanceof List<?> l) {
                out.put(key, asList(l));
            }
        }
        return out;
    }

    /** 供调试：当前语言全部键（只读）。 */
    public static Map<String, Object> entries() {
        return Collections.unmodifiableMap(current);
    }

    /** 语言码小写形式，用于 `Language` 展示（保留扩展位）。 */
    public static String lowerCode() {
        return code.toLowerCase(Locale.ROOT);
    }
}
