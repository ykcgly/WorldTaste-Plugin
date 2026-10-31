package com.haiman233.worldtaste.load;

import com.haiman233.worldtaste.WT;
import com.haiman233.worldtaste.WorldTastePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import com.haiman233.worldtaste.util.Lang;

/**
 * 从 jar 资源加载 YAML。
 *
 * <p>R6：加载期文件名缓存（parse-once）。{@code preloadDisplays} 与各 Loader 会访问同一组内容文件
 * （items.yml/mb_machines.yml/… 共 10 个），旧实现每个文件被解析两次（含 2.5MB 的 items.yml、1.8MB
 * 的 mb_machines.yml）。缓存后每个文件在单次加载中只解析一次，第二次访问为 HashMap 命中。
 *
 * <p>安全性：经核查全部 Loader 仅读取配置、从不写入（无 {@code y.set/section.set}），故跨调用方共享同一
 * {@link YamlConfiguration} 实例行为等价。缓存为加载期单线程填充，与 {@code WT.preload} 等同为 HashMap。
 * 加载结束后由 {@link Setup#loadAll()} 调用 {@link #clearCache()} 释放解析树（长稳：避免长期持有 ~MB 级
 * 解析对象树；经核查无 Loader 以字段形式持久持有 ConfigurationSection，释放安全）。
 */
public final class Yaml {

    private Yaml() {}

    /** 加载期文件名缓存：filename -> 已解析配置（只读共享，加载结束释放）。 */
    private static final Map<String, YamlConfiguration> CACHE = new HashMap<>();

    public static YamlConfiguration loadResource(WorldTastePlugin plugin, String name) {
        YamlConfiguration cached = CACHE.get(name);
        if (cached != null) return cached;
        YamlConfiguration cfg = doLoad(plugin, name);
        CACHE.put(name, cfg);
        return cfg;
    }

    private static YamlConfiguration doLoad(WorldTastePlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                WT.log(Lang.get("log.yaml.001") + name);
                return new YamlConfiguration();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            WT.log(Lang.get("log.yaml.002") + name + Lang.get("log.yaml.003") + e);
            return new YamlConfiguration();
        }
    }

    /** 加载完成后释放解析树缓存（{@link Setup#loadAll()} 末尾调用）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
