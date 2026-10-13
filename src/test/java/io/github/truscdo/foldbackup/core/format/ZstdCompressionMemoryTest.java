package io.github.truscdo.foldbackup.core.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方逐级推导值：zstd v1.5.6 {@code lib/compress/clevels.h} 的
 * {@code ZSTD_defaultCParameters[0]} 代入官方 wiki 的分项公式。
 * <p>
 * 这些是硬编码表的回归护栏 —— 若数值变动，必须能追到官方表或公式的变更。
 */
class ZstdCompressionMemoryTest {

    @Test
    void derivedBytesMatchTheOfficialTable() {
        assertEquals(851_968L, ZstdCompressionMemory.singleThreadStreamBytes(1));
        assertEquals(1_572_864L, ZstdCompressionMemory.singleThreadStreamBytes(2));
        assertEquals(3_145_728L, ZstdCompressionMemory.singleThreadStreamBytes(3));
        assertEquals(4_456_448L, ZstdCompressionMemory.singleThreadStreamBytes(4));
        assertEquals(6_029_312L, ZstdCompressionMemory.singleThreadStreamBytes(5));
        assertEquals(6_029_312L, ZstdCompressionMemory.singleThreadStreamBytes(6));
        assertEquals(9_699_328L, ZstdCompressionMemory.singleThreadStreamBytes(7));
        assertEquals(19_136_512L, ZstdCompressionMemory.singleThreadStreamBytes(9));
        assertEquals(63_176_704L, ZstdCompressionMemory.singleThreadStreamBytes(12));
        assertEquals(38_010_880L, ZstdCompressionMemory.singleThreadStreamBytes(13));
        assertEquals(71_565_312L, ZstdCompressionMemory.singleThreadStreamBytes(15));
        assertEquals(38_010_880L, ZstdCompressionMemory.singleThreadStreamBytes(16));
        assertEquals(92_536_832L, ZstdCompressionMemory.singleThreadStreamBytes(19));
        assertEquals(201_588_736L, ZstdCompressionMemory.singleThreadStreamBytes(20));
        assertEquals(402_915_328L, ZstdCompressionMemory.singleThreadStreamBytes(21));
        assertEquals(805_568_512L, ZstdCompressionMemory.singleThreadStreamBytes(22));
    }

    /** level 19 的推导值 88.25 MiB 与本仓库实测 89.1 MiB 相差不到 1%，此处固定推导侧。 */
    @Test
    void level19DerivationStaysWithinOnePercentOfTheMeasuredValue() {
        long measured = 93_428_122L;
        long derived = ZstdCompressionMemory.singleThreadStreamBytes(19);
        assertTrue(Math.abs(derived - measured) * 100 / measured <= 1,
                "derived " + derived + " vs measured " + measured);
    }

    /** 官方表非单调（chainLog 主导），因此实现必须查表，不能按档位平滑外推。 */
    @Test
    void tableIsNotMonotonicInLevel() {
        assertTrue(ZstdCompressionMemory.singleThreadStreamBytes(12)
                > ZstdCompressionMemory.singleThreadStreamBytes(13));
        assertTrue(ZstdCompressionMemory.singleThreadStreamBytes(15)
                > ZstdCompressionMemory.singleThreadStreamBytes(16));
        assertTrue(ZstdCompressionMemory.singleThreadStreamBytes(22)
                > ZstdCompressionMemory.singleThreadStreamBytes(12));
    }

    @Test
    void outOfRangeLevelsClampToZstdDefaults() {
        assertEquals(ZstdCompressionMemory.singleThreadStreamBytes(3),
                ZstdCompressionMemory.singleThreadStreamBytes(0));
        assertEquals(ZstdCompressionMemory.singleThreadStreamBytes(3),
                ZstdCompressionMemory.singleThreadStreamBytes(-5));
        assertEquals(ZstdCompressionMemory.singleThreadStreamBytes(22),
                ZstdCompressionMemory.singleThreadStreamBytes(23));
        assertEquals(ZstdCompressionMemory.singleThreadStreamBytes(22),
                ZstdCompressionMemory.singleThreadStreamBytes(100));
    }
}
