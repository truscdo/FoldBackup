package io.github.truscdo.foldbackup.core.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方流式解压分项：{@code Window_Size + 3 × min(128 KiB, Window_Size)}。
 */
class ZstdDecompressionMemoryTest {

    @Test
    void derivedBytesFollowTheFrameWindow() {
        // level 6 → windowLog 21（2 MiB）；块项为 min(128 KiB, 窗口) = 128 KiB。
        assertEquals(2_097_152L + 3 * 131_072L, ZstdDecompressionMemory.singleThreadStreamBytes(6));
        // level 19 → windowLog 23（8 MiB），即归档 LRH 帧的窗口。
        assertEquals(8_388_608L + 3 * 131_072L, ZstdDecompressionMemory.singleThreadStreamBytes(19));
        // level 22 → windowLog 27（128 MiB），等于解码端默认上限。
        assertEquals(134_217_728L + 3 * 131_072L, ZstdDecompressionMemory.singleThreadStreamBytes(22));
    }

    @Test
    void outOfRangeLevelsClampToZstdDefaults() {
        assertEquals(ZstdDecompressionMemory.singleThreadStreamBytes(3),
                ZstdDecompressionMemory.singleThreadStreamBytes(0));
        assertEquals(ZstdDecompressionMemory.singleThreadStreamBytes(22),
                ZstdDecompressionMemory.singleThreadStreamBytes(23));
    }

    /**
     * 常见档位下解压上下文小于压缩上下文（解压只有窗口与块缓冲）。
     * level 1 是例外：fast 策略的压缩上下文只有哈希表 832 KiB，小于解压的 896 KiB。
     */
    @Test
    void decompressionIsCheaperThanCompressionAtCommonLevels() {
        for (int level : new int[] { 3, 6, 9, 19, 22 }) {
            assertTrue(ZstdDecompressionMemory.singleThreadStreamBytes(level)
                    < ZstdCompressionMemory.singleThreadStreamBytes(level), "level " + level);
        }
    }
}
