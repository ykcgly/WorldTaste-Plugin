package com.haiman233.worldtaste.guide;

import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.guide.SurvivalSlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 嵌套物品组工厂。
 *
 * <p>为什么用「匿名直接子类」：JEG（JustEnoughGuide）打开嵌套组时，只对
 * 「恰好是 NestedItemGroup 类型、或其匿名直接子类」的分组使用它自己的渲染
 * （底部收藏栏、实时搜索等完整增强），具名子类会被当普通分组渲染成近乎空白页。
 * 因此本工厂创建 NestedItemGroup 的匿名子类，让 JEG 正常接管嵌套菜单。</p>
 *
 * <p>装饰分隔板（{@link DecorativeSubGroup}）作为普通 SubItemGroup 挂在组内：
 * JEG 渲染时正常显示为普通原版玻璃板，点击由 JegGuideListener 取消事件；
 * 原版指南（JEG 未接管、才走到覆写的 open）由 {@link #openVanilla} 渲染，
 * 玻璃板绑定空点击处理器——两种指南下都打不开、拿不走。</p>
 */
public final class WTNestedGroup {

    private WTNestedGroup() {}

    /** 渲染条目：按注册顺序与 tier 排版。 */
    private record Entry(int seq, int tier, SubItemGroup group) {}

    /**
     * 创建嵌套组（匿名直接子类——JEG 以 getSuperclass() == NestedItemGroup
     * 且 isAnonymousClass() 识别并接管渲染；原版指南则虚分派到覆写的 open）。
     */
    public static NestedItemGroup create(NamespacedKey key, ItemStack display, int tier) {
        List<Entry> entries = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        return new NestedItemGroup(key, display, tier) {
            @Override
            public void addSubGroup(SubItemGroup sub) {
                super.addSubGroup(sub);
                entries.add(new Entry(seq.getAndIncrement(), sub.getTier(), sub));
            }

            @Override
            public void removeSubGroup(SubItemGroup sub) {
                super.removeSubGroup(sub);
                entries.removeIf(e -> e.group() == sub);
            }

            @Override
            public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
                // 只有原版 Slimefun 指南会走到这里；JEG 用它自己的 openNestedItemGroup
                openVanilla(p, profile, mode, this, entries, 1);
            }
        };
    }

    /** 原版指南路径的嵌套菜单渲染：真实子组可点击，装饰玻璃板仅占位、点击无效。 */
    private static void openVanilla(Player p, PlayerProfile profile, SlimefunGuideMode mode,
                                    NestedItemGroup group, List<Entry> entries, int page) {
        // 每次打开时解析渲染条目：不可见子组（Seasonal 等）跳过
        List<Entry> visible = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.group().isVisibleInNested(p)) continue;
            visible.add(e);
        }
        visible.sort(Comparator.comparingInt(Entry::tier).thenComparingInt(Entry::seq));

        if (mode == SlimefunGuideMode.SURVIVAL_MODE) {
            profile.getGuideHistory().add(group, page);
        }
        ChestMenu menu = new ChestMenu(Slimefun.getLocalization().getMessage(p, "guide.title.main"));
        SurvivalSlimefunGuide guide = (SurvivalSlimefunGuide) Slimefun.getRegistry().getSlimefunGuide(mode);
        menu.setEmptySlotsClickable(false);
        menu.addMenuOpeningHandler(SoundEffect.GUIDE_BUTTON_CLICK_SOUND::playFor);
        guide.createHeader(p, profile, menu);
        menu.addItem(1, ChestMenuUtils.getBackButton(p, "",
                ChatColor.GRAY + Slimefun.getLocalization().getMessage(p, "guide.back.guide")));
        menu.addMenuClickHandler(1, (pl, slot, item, action) -> {
            SlimefunGuide.openMainMenu(profile, mode, profile.getGuideHistory().getMainMenuPage());
            return false;
        });

        int perPage = 36;
        int pages = (visible.size() + perPage - 1) / perPage;
        int start = Math.max(0, perPage * (page - 1));
        int slot = 9;
        for (int i = start; i < visible.size() && slot < 45; i++, slot++) {
            SubItemGroup subgroup = visible.get(i).group();
            menu.addItem(slot, subgroup.getItem(p));
            if (subgroup instanceof DecorativeSubGroup) {
                // 装饰分隔板：普通原版玻璃板占位；绑定空点击处理器——打不开、拿不走
                menu.addMenuClickHandler(slot, (pl, s, item, action) -> false);
            } else {
                menu.addMenuClickHandler(slot, (pl, s, item, action) -> {
                    SlimefunGuide.openItemGroup(profile, subgroup, mode, 1);
                    return false;
                });
            }
        }

        menu.addItem(46, ChestMenuUtils.getPreviousButton(p, page, pages));
        menu.addMenuClickHandler(46, (pl, s, item, action) -> {
            int prev = page - 1;
            if (prev != page && prev > 0) {
                openVanilla(p, profile, mode, group, entries, prev);
            }
            return false;
        });
        menu.addItem(52, ChestMenuUtils.getNextButton(p, page, pages));
        menu.addMenuClickHandler(52, (pl, s, item, action) -> {
            int next = page + 1;
            if (next != page && next <= pages) {
                openVanilla(p, profile, mode, group, entries, next);
            }
            return false;
        });
        menu.open(p);
    }
}