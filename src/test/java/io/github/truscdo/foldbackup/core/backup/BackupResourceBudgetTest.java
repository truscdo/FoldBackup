package io.github.truscdo.foldbackup.core.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupResourceBudgetTest {

    @Test
    void autoCpuWorkersUseOneThreadOnSmallMachines() {
        assertEquals(1, BackupResourceBudget.cpuWorkers(0, 1, 8));
        assertEquals(1, BackupResourceBudget.cpuWorkers(0, 2, 8));
        assertEquals(2, BackupResourceBudget.cpuWorkers(0, 4, 8));
        assertEquals(3, BackupResourceBudget.cpuWorkers(0, 6, 8));
    }

    @Test
    void cpuWorkersRespectRequestedAndWorkLimits() {
        assertEquals(1, BackupResourceBudget.cpuWorkers(8, 4, 1));
        assertEquals(4, BackupResourceBudget.cpuWorkers(8, 4, 8));
        assertEquals(2, BackupResourceBudget.cpuWorkers(2, 8, 8));
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
}
