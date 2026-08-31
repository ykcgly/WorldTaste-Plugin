package com.haiman233.worldtaste.machines;

/**
 * 声明「运行时每刻耗电量」的机器。
 *
 * <p>Slimefun 的 {@link io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent}
 * 只暴露储电容量（{@code getCapacity()}），并没有耗电量接口。实现本接口后，
 * {@code ItemsLoader} 在注册时会自动把「储电容量 / 运行时耗电」两行写进物品 lore——
 * 数值直接取自机器类的常量，改 Java 常量即自动同步，不需要在 yml 里再维护一份。</p>
 */
public interface PowerConsumer {

    /**
     * 运行时每刻耗电量（J/刻）。
     *
     * @return 耗电量；返回 0 表示不在 lore 中展示耗电行（只展示储电容量）
     */
    int getConsumption();
}
