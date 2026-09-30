package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.task.StepProgress;

/**
 * Typed progress steps for the backup workflow.
 * <p>
 * Each record models one work phase with structured, type-safe fields.
 * Consumers use {@code instanceof} pattern matching to extract fields.
 */
public sealed interface BackupStep extends StepProgress {

    /**
     * World-scan progress.
     * <p>
     * {@code regionsDiscovered}/{@code filesDiscovered} count entries whose scan
     * task was submitted, while {@code regionsProcessed}/{@code filesProcessed}
     * count entries that finished successfully and {@code failures} counts tasks
     * that threw. Discovered therefore leads processed for the whole scan, and a
     * failed entry never advances the processed counters. Discovered counts are
     * not final totals until {@code complete}; completion does not imply success.
     * Paths identify the last discovered entries, not the currently active workers.
     */
    record ScanningWorld(int regionsProcessed, int filesProcessed,
            String lastDiscoveredRegion, String lastDiscoveredFile,
            boolean complete, int regionsDiscovered, int filesDiscovered,
            int failures) implements BackupStep {
        public ScanningWorld(int regionsProcessed, int filesProcessed, String lastDiscoveredRegion, String lastDiscoveredFile,
                boolean complete) {
            this(regionsProcessed, filesProcessed, lastDiscoveredRegion, lastDiscoveredFile, complete, regionsProcessed,
                    filesProcessed, 0);
        }

        @Override
        public String phase() {
            return "SCANNING_WORLD";
        }

        public long processedCount() { return (long) regionsProcessed + filesProcessed; }
        public long discoveredCount() { return (long) regionsDiscovered + filesDiscovered; }
        public long finishedCount() { return processedCount() + failures; }
    }

    /** Preparing the in-memory manifest; nothing has been committed yet. */
    record PreparingSnapshot(ScanStats scanStats) implements BackupStep {
        @Override
        public String phase() {
            return "PREPARING_SNAPSHOT";
        }
    }

    /** Atomic index commit (with optional retention), followed by blob cleanup. */
    record CommittingSnapshot(String snapshotId) implements BackupStep {
        @Override
        public String phase() {
            return "COMMITTING_SNAPSHOT";
        }
    }

    record Completed(long epochSeconds, String snapshotId, ScanStats scanStats,
            long newBytes) implements BackupStep {
        @Override
        public String phase() {
            return "COMPLETED";
        }
    }

    record Failed(String errorMessage) implements BackupStep {
        @Override
        public String phase() {
            return "FAILED";
        }
    }

    /** The snapshot exists; the operation failed only after the index commit. */
    record CleanupFailed(long epochSeconds, String snapshotId, String errorMessage) implements BackupStep {
        @Override
        public String phase() {
            return "CLEANUP_FAILED";
        }
    }
}
