package com.haiman233.worldtaste.util;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/**
 * 原版材质中文名表：覆盖玩家提示里实际会出现的材质集合（作物种植要求、副手工具、
 * 钓鱼产出兜底名等）。未收录的材质回退为小写枚举名（与旧行为一致），不抛异常。
 *
 * <p>不用 Component.translatable 的原因：这些提示是纯文本 legacy 消息（聊天栏/动作栏
 * 混用颜色码），本服面向中文玩家，固定中文名即可；且 fishing.yml 等数据可随时引用
 * 新材质，表外的回退保证不缺字。</p>
 */
public final class ZhNames {

    private ZhNames() {}

    private static final Map<String, String> MAP = new HashMap<>();

    private static void put(String key, String zh) {
        MAP.put(key, zh);
    }

    static {
        // 作物种植要求（Behaviors.inferPlantOn + crops.yml plantOn）
        put("FARMLAND", "耕地");
        put("SOUL_SAND", "灵魂沙");
        put("SOUL_SOIL", "灵魂土");
        put("SAND", "沙子");
        put("RED_SAND", "红沙");
        put("DIRT", "泥土");
        put("GRASS_BLOCK", "草方块");
        put("COARSE_DIRT", "砂土");
        put("PODZOL", "灰化土");
        put("ROOTED_DIRT", "缠根泥土");
        put("MUD", "泥巴");
        put("JUNGLE_LOG", "丛林原木");
        put("JUNGLE_WOOD", "丛林木");
        put("STRIPPED_JUNGLE_LOG", "去皮丛林原木");
        put("STRIPPED_JUNGLE_WOOD", "去皮丛林木");
        put("END_STONE", "末地石");
        put("MYCELIUM", "菌丝体");
        put("NETHERRACK", "下界岩");
        put("CRIMSON_NYLIUM", "绯红菌岩");
        put("WARPED_NYLIUM", "诡异菌岩");
        put("SANDSTONE", "砂岩");
        put("CLAY", "黏土块");
        put("GRAVEL", "沙砾");
        put("SNOW_BLOCK", "雪块");
        put("ICE", "冰");
        put("OBSIDIAN", "黑曜石");
        put("STONE", "石头");
        put("DEEPSLATE", "深板岩");
        put("MOSS_BLOCK", "苔藓块");
        // 作物本体（提示扩展备用）
        put("WHEAT", "小麦");
        put("CARROTS", "胡萝卜");
        put("POTATOES", "马铃薯");
        put("BEETROOTS", "甜菜根");
        put("NETHER_WART", "下界疣");
        put("SUGAR_CANE", "甘蔗");
        put("CACTUS", "仙人掌");
        put("COCOA", "可可豆");
        put("SWEET_BERRY_BUSH", "甜浆果丛");
        put("BAMBOO", "竹子");
        // 消耗品副手工具（data/consumables.yml offhandTool）
        put("FLINT_AND_STEEL", "打火石");
        put("SHEARS", "剪刀");
        // 钓鱼产出（data/fishing.yml 引用的原版物品）
        put("LILY_PAD", "睡莲");
        put("TADPOLE_BUCKET", "蝌蚪桶");
        put("AXOLOTL_BUCKET", "美西螈桶");
        put("SALMON", "生鲑鱼");
        put("COD", "生鳕鱼");
        put("PUFFERFISH", "河豚");
        put("TROPICAL_FISH", "热带鱼");
        put("SEAGRASS", "海草");
        put("SEA_PICKLE", "海泡菜");
        put("KELP", "海带");
        put("HEART_OF_THE_SEA", "海洋之心");
        put("NAUTILUS_SHELL", "鹦鹉螺壳");
        put("STRING", "线");
        put("BONE", "骨头");
        put("INK_SAC", "墨囊");
        put("SPIDER_EYE", "蜘蛛眼");
        put("PRISMARINE_SHARD", "海晶碎片");
        put("SADDLE", "鞍");
        put("LEATHER", "皮革");
        put("ROTTEN_FLESH", "腐肉");
        put("GUNPOWDER", "火药");
        put("SLIME_BALL", "黏液球");
        put("EGG", "鸡蛋");
        put("PAPER", "纸");
        put("BOOK", "书");
        put("EXPERIENCE_BOTTLE", "附魔之瓶");
        put("NAME_TAG", "命名牌");
    }

    /** 材质中文名；未收录返回 null（调用方可回退旧英文格式）。 */
    public static String of(Material m) {
        if (m == null) return null;
        return MAP.get(m.name());
    }

    /** 材质中文名；未收录回退为小写枚举名（下划线转空格，与旧行为一致）。 */
    public static String orEnglish(Material m) {
        String zh = of(m);
        return zh != null ? zh : m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
