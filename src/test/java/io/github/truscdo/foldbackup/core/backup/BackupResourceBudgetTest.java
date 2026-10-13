package io.github.truscdo.foldbackup.core.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupResourceBudgetTest {

    @Test
    void regionQuotaAutoUsesAllProcessorsAndRespectsWorkItems() {
        long mib = 1L << 20;
        assertEquals(2, BackupResourceBudget.regionQuota(8, 1000 * mib, 0, mib, 2, 0).workers());
        assertEquals(4, BackupResourceBudget.regionQuota(8, 1000 * mib, 0, mib, 4, 0).workers());
        assertEquals(1, BackupResourceBudget.regionQuota(1, 1000 * mib, 0, mib, 4, 0).workers());
        assertEquals(3, BackupResourceBudget.regionQuota(8, 3 * mib, 0, mib, 8, 0).workers());
        assertEquals(1, BackupResourceBudget.regionQuota(0, 3 * mib, 0, mib, 8, 0).workers());
    }

    @Test
    void regionQuotaRequestedThreadsAreClampedByProcessorsAndWorkItems() {
        long mib = 1L << 20;
        assertEquals(1, BackupResourceBudget.regionQuota(8, 1000 * mib, 0, mib, 4, 1).workers());
        assertEquals(4, BackupResourceBudget.regionQuota(8, 1000 * mib, 0, mib, 4, 8).workers());
        assertEquals(2, BackupResourceBudget.regionQuota(8, 1000 * mib, 0, mib, 8, 2).workers());
        assertEquals(2, BackupResourceBudget.regionQuota(2, 1000 * mib, 0, mib, 8, 0).workers());
    }

    @Test
    void modRegionQuotaLeavesHalfTheProcessorsForTheGame() {
        long mib = 1L << 20;
        assertEquals(1, BackupResourceBudget.modRegionQuota(64, 1000 * mib, 0, mib, 2, 0).workers());
        assertEquals(3, BackupResourceBudget.modRegionQuota(64, 1000 * mib, 0, mib, 6, 0).workers());
        // 显式请求仍以全部处理器为上限，不被自动档的一半限制。
        assertEquals(8, BackupResourceBudget.modRegionQuota(64, 1000 * mib, 0, mib, 8, 8).workers());
    }

    @Test
    void regionQuotaWindowIsTwiceTheWorkersCappedByTheWorkItems() {
        long mib = 1L << 20;
        assertEquals(16, BackupResourceBudget.regionQuota(100, 1000 * mib, 0, mib, 8, 8).inFlightWindow());
        assertEquals(5, BackupResourceBudget.regionQuota(5, 1000 * mib, 0, mib, 8, 8).inFlightWindow());
        assertEquals(1, BackupResourceBudget.regionQuota(0, 1000 * mib, 0, mib, 8, 0).inFlightWindow());
    }

    @Test
    void autoMemoryBudgetUsesHeapAndCurrentHeadroom() {
        long mib = 1L << 20;
        assertEquals(200 * mib,
                BackupResourceBudget.memoryBudgetBytes(0, 1000 * mib, 100 * mib));
        assertEquals(64 * mib,
                BackupResourceBudget.memoryBudgetBytes(64, 1000 * mib, 100 * mib));
    }

    @Test
    void streamingMemoryDependsOnCompressionLevel() {
        long mib = 1L << 20;
        assertEquals(32 * mib, BackupResourceBudget.streamingTaskBytes(6));
        assertEquals(256 * mib, BackupResourceBudget.streamingTaskBytes(19));
    }

    @Test
    void compressMemoryIsTheOfficialDerivationTimesMargin() {
        // 官方推导值 ×13/10（值取自 ZstdCompressionMemory 的逐级推导）。
        assertEquals(7_838_105L, BackupResourceBudget.compressTaskBytes(6));
        assertEquals(120_297_881L, BackupResourceBudget.compressTaskBytes(19));
        assertEquals(1_047_239_065L, BackupResourceBudget.compressTaskBytes(22));
        // 官方推导值在所有档位都比旧的级别档常数更紧；且随级别单调增。
        assertTrue(BackupResourceBudget.compressTaskBytes(6) < BackupResourceBudget.streamingTaskBytes(6));
        assertTrue(BackupResourceBudget.compressTaskBytes(19) < BackupResourceBudget.streamingTaskBytes(19));
        assertTrue(BackupResourceBudget.compressTaskBytes(0) == BackupResourceBudget.compressTaskBytes(3));
        assertTrue(BackupResourceBudget.compressTaskBytes(23) == BackupResourceBudget.compressTaskBytes(22));
    }

    @Test
    void decompressMemoryIsTheExactAllocationForTheFrameLevel() {
        // level 6 → 2 MiB 窗口 + 3 × 128 KiB；level 19（归档帧）→ 8 MiB 窗口 + 3 × 128 KiB。
        assertEquals(2_097_152L + 3 * 131_072L, BackupResourceBudget.decompressTaskBytes(6));
        assertEquals(8_388_608L + 3 * 131_072L, BackupResourceBudget.decompressTaskBytes(19));
    }

    @Test
    void readThenRecompressCountsBothContexts() {
        // 合并导入：压缩上下文随输出级别，解码上下文随源帧级别。
        assertEquals(BackupResourceBudget.compressTaskBytes(6) + BackupResourceBudget.decompressTaskBytes(19),
                BackupResourceBudget.compressAndDecodeTaskBytes(6, 19));
        assertTrue(BackupResourceBudget.compressAndDecodeTaskBytes(6, 19)
                > BackupResourceBudget.compressTaskBytes(6));
    }
}
