package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;

/** Independent scan and compression estimates; all budgets still allow one worker. */
final class DiffResourceBudget {
    static final int BLOCK_SIZE = 64 * 1024;
    // One decoder at a time (target then base): 128 MiB window allowance plus buffers/metadata.
    static final long SCAN_TASK_BYTES = 144L << 20;
    record Compression(int workers, int window, long bufferBytes) {}

    static int scanWorkers(int count, int requested, long budget) {
        return workers(count, requested, budget, SCAN_TASK_BYTES);
    }

    static Compression compression(int count, int requested, int level, long budget) {
        long buffers = Math.max(2L * BLOCK_SIZE, Math.min(64L << 20, budget / 8));
        int workers = workers(count, requested, Math.max(0, budget - buffers),
                BackupResourceBudget.streamingTaskBytes(level));
        return new Compression(workers, (int) Math.max(1, Math.min(count, 2L * workers)), buffers);
    }

    private static int workers(int count, int requested, long budget, long taskBytes) {
        return (int) Math.max(1, Math.min(Math.min(Math.max(1, count), Runtime.getRuntime().availableProcessors()),
                Math.min(requested <= 0 ? Integer.MAX_VALUE : requested, budget / taskBytes)));
    }
    private DiffResourceBudget() {}
}
