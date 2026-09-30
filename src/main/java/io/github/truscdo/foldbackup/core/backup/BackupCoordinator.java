package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.core.LogUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Core backup orchestrator.
 * <p>
 * Flow:
 * <ol>
 * <li>Load the latest snapshot for source-metadata change detection</li>
 * <li>Scan world: source-metadata fast paths + typed content-hash lookup</li>
 * <li>Commit snapshot and retention changes to index.db once</li>
 * <li>GC orphaned blobs after the commit</li>
 * </ol>
 * <p>
 * Progress is reported by updating the {@link TaskStatus} passed to
 * {@link #runBackup(TaskStatus)}. The caller provides a status that is
 * already in {@link TaskStatus.Phase#RUNNING} state.
 */
public class BackupCoordinator {

    private static final LogUtil LOG = LogUtil.getLogger(BackupCoordinator.class);
    private static final int DIAGNOSTIC_FILE_LIMIT = 20;

    private final BackupContext context;
    private final SnapshotStore snapshotManager;
    private final RegionProcessor regionProcessor;
    private final RetentionPolicy retentionPolicy;

    public BackupCoordinator(BackupContext context,
            SnapshotStore snapshotManager,
            RegionProcessor regionProcessor,
            RetentionPolicy retentionPolicy) {
        this.context = context;
        this.snapshotManager = snapshotManager;
        this.regionProcessor = regionProcessor;
        this.retentionPolicy = retentionPolicy;
    }

    /**
     * Execute a full backup cycle, updating the provided {@code status}
     * with progress messages.
     *
     * @param status a task status already in {@link TaskStatus.Phase#RUNNING}
     * @return the created snapshot manifest
     */
    public SnapshotManifest runBackup(TaskStatus status) throws IOException {
        return runBackup(status, null);
    }

    /**
     * Publish the same immutable steps to task status and an optional observer.
     * The observer runs on the backup thread and must not access game-thread state.
     * Observer failures are logged without changing the backup's outcome.
     * The caller still owns task registry completion after its own finalization.
     */
    public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
        Consumer<BackupStep> progress = step -> publish(status, listener, step);
        try (SnapshotStore.WriteSession ignored = snapshotManager.beginWrite()) {
            if (!Files.isDirectory(context.worldDir()))
                throw new IOException("World directory does not exist: " + context.worldDir());
            long indexBytes = snapshotManager.prepareBackup();
            progress.accept(new BackupStep.ScanningWorld(0, 0, "", "", false));

            // Capture the identity once; the readable ID is derived from this epoch in UTC.
            long snapshotEpochSeconds = Instant.now().getEpochSecond();
            String snapshotId = SnapshotStore.generateSnapshotId(snapshotEpochSeconds);

            // Source-metadata reuse only needs the latest committed manifest.
            SnapshotManifest latestSnapshot = snapshotManager.loadLatestSnapshot();

            // Scan world with per-file progress tracking
            ScanProgressTracker scanTracker = new ScanProgressTracker(progress);
            ScanResult scan;
            long maxBlobBytes = retentionPolicy != null && retentionPolicy.isEnabled()
                    ? retentionPolicy.getMaxSizeBytes()
                    : 0;
            try (var writes = regionProcessor.beginBackupWrites(indexBytes, maxBlobBytes)) {
                writes.check();
                scan = regionProcessor.scanWorld(context.worldDir(), latestSnapshot, scanTracker, snapshotId);
                writes.check();
            }

            LOG.info("Scan complete: {} regions, {} files, {} errors",
                    scan.getRegionCount(), scan.getFileCount(), scan.getErrors().size());

            String scanFailure = null;
            if (!scan.getErrors().isEmpty()) {
                StringBuilder sb = new StringBuilder("Scan failed with errors:\n");
                for (String err : scan.getErrors()) {
                    sb.append("  - ").append(err).append("\n");
                }
                scanFailure = sb.toString().stripTrailing();
                LOG.warn(scanFailure);
            }

            // 目录诊断只在扫描结果可疑时输出：正常备份不解析目录结构，
            // 避免大型世界的 region/ 条目把生产日志刷爆。
            if (needsScanDiagnostics(scan)) {
                logScanDiagnostics(context.worldDir());
            }

            scanTracker.logFinal();

            if (scanFailure != null)
                throw new IOException(scanFailure);

            ScanStats scanStats = new ScanStats(
                    scan.getRegionCount(), scan.getChangedRegions(),
                    scan.getFileCount(), scan.getChangedFiles());

            progress.accept(new BackupStep.PreparingSnapshot(scanStats));

            // Build and write snapshot manifest

            SnapshotManifest manifest = new SnapshotManifest(
                    snapshotId, snapshotEpochSeconds, context.worldName());
            manifest.setMinecraftVersion(context.minecraftVersion());

            scan.populateManifest(manifest);

            // Commit the new snapshot and retention changes together, then GC.
            progress.accept(new BackupStep.CommittingSnapshot(snapshotId));
            snapshotManager.saveSnapshot(manifest, retentionPolicy);

            // Final summary
            progress.accept(new BackupStep.Completed(
                    snapshotEpochSeconds, snapshotId, scanStats,
                    scan.getNewBytes()));

            return manifest;

        } catch (Exception e) {
            LOG.error("Backup failed", e);
            if (e instanceof SnapshotStore.CommittedCleanupException committed) {
                progress.accept(new BackupStep.CleanupFailed(committed.epochSeconds(), committed.snapshotId(),
                        committed.getMessage()));
            } else {
                progress.accept(new BackupStep.Failed("Backup failed: " + e.getMessage()));
            }
            throw e;
        }
    }

    private static void publish(TaskStatus status, Consumer<BackupStep> listener, BackupStep step) {
        switch (step) {
            case BackupStep.Completed completed -> status.setArtifact(completed.epochSeconds(), completed.snapshotId());
            case BackupStep.CleanupFailed failed -> {
                status.setArtifact(failed.epochSeconds(), failed.snapshotId());
                status.setFailed(failed.errorMessage());
            }
            case BackupStep.Failed failed -> status.setFailed(failed.errorMessage());
            default -> { }
        }
        status.setStepProgress(step);
        if (listener != null) {
            try {
                listener.accept(step);
            } catch (RuntimeException e) {
                LOG.warn("Backup progress observer failed", e);
            }
        }
    }

    // ── Debug helpers ──

    /**
     * 扫描结果是否可疑到需要输出目录诊断：0 个 region，或存在扫描错误。
     * <p>
     * 正常的备份路径返回 {@code false}，此时不触碰目录结构。
     */
    static boolean needsScanDiagnostics(ScanResult scan) {
        return scan.getRegionCount() == 0 || !scan.getErrors().isEmpty();
    }

    /** {@code region/} 目录条目计数（诊断汇总的有界输出）。 */
    record RegionDirCount(int total, int mca, int mcc) {
    }

    /**
     * 统计 {@code region/} 下的条目数量，不保留文件名（大目录不占用内存）。
     *
     * @throws IOException 目录不可读取时
     */
    static RegionDirCount countRegionDirEntries(Path regionDir) throws IOException {
        int total = 0, mca = 0, mcc = 0;
        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                total++;
                String name = file.getFileName().toString();
                if (name.endsWith(".mca"))
                    mca++;
                else if (name.endsWith(".mcc"))
                    mcc++;
            }
        }
        return new RegionDirCount(total, mca, mcc);
    }

    /**
     * 输出世界目录结构，用于诊断"扫描不到区块文件"一类问题。
     * <p>
     * 仅由 {@link #runBackup} 在 {@link #needsScanDiagnostics} 为真时调用，
     * 正常备份路径零开销。输出分两层：目录汇总走 INFO；
     * {@code region/} 文件名仅在 DEBUG 启用时打印前 20 个。
     */
    private void logScanDiagnostics(Path worldDir) {
        LOG.info("--- Backup scan diagnostics ---");
        LOG.info("worldDir = {}", worldDir.toAbsolutePath());
        LOG.info("  exists = {}", Files.isDirectory(worldDir));

        if (Files.isDirectory(worldDir)) {
            // Check immediate children（一级子目录数量有界，可直接列出）
            try (Stream<Path> children = Files.list(worldDir)) {
                children.filter(Files::isDirectory).forEach(d -> LOG.info("  subdir: {}", d.getFileName()));
            } catch (IOException e) {
                LOG.warn("  failed to list worldDir children: {}", e.getMessage());
            }

            // Check region directory specifically
            Path regionDir = worldDir.resolve("region");
            LOG.info("  region/ exists = {}", Files.isDirectory(regionDir));

            if (Files.isDirectory(regionDir)) {
                logRegionDirEntries(regionDir);
            }
        }
        LOG.info("--- end diagnostics ---");
    }

    /** 汇总 {@code region/} 下的条目数量；DEBUG 启用时最多列出 20 个文件名。 */
    private void logRegionDirEntries(Path regionDir) {
        RegionDirCount count;
        try {
            count = countRegionDirEntries(regionDir);
        } catch (IOException e) {
            LOG.warn("  failed to list region dir: {}", e.getMessage());
            return;
        }
        LOG.info("  region/ entries = {} (.mca = {}, .mcc = {})",
                count.total(), count.mca(), count.mcc());

        if (!LOG.isDebugEnabled())
            return;
        try (Stream<Path> files = Files.list(regionDir)) {
            files.limit(DIAGNOSTIC_FILE_LIMIT).forEach(f -> LOG.debug("    {}", f.getFileName()));
            if (count.total() > DIAGNOSTIC_FILE_LIMIT)
                LOG.debug("    ... {} more entries", count.total() - DIAGNOSTIC_FILE_LIMIT);
        } catch (IOException e) {
            LOG.warn("  failed to list region dir: {}", e.getMessage());
        }
    }

    /**
     * Rate-limited scan progress tracker that updates the TaskStatus
     * every 1 second. Receives callbacks from RegionProcessor during
     * world scanning.
     */
    private static class ScanProgressTracker implements java.util.function.Consumer<ScanEvent> {

        private final Consumer<BackupStep> progress;
        private int regionsProcessed;
        private int filesProcessed;
        private int regionsDiscovered, filesDiscovered, failures;
        private String lastDiscoveredRegion;
        private String lastDiscoveredFile;
        private long lastLogTime;

        ScanProgressTracker(Consumer<BackupStep> progress) {
            this.progress = progress;
            this.regionsProcessed = 0;
            this.filesProcessed = 0;
            this.lastDiscoveredRegion = "";
            this.lastDiscoveredFile = "";
            this.lastLogTime = System.currentTimeMillis();
        }

        @Override
        public void accept(ScanEvent event) {
            switch (event) {
                case ScanEvent.Region r -> {
                    regionsDiscovered++;
                    lastDiscoveredRegion = r.relPath();
                }
                case ScanEvent.File f -> {
                    filesDiscovered++;
                    lastDiscoveredFile = f.relPath();
                }
                case ScanEvent.Completed c -> {
                    if (c.failed()) failures++;
                    if (!c.failed()) {
                        if (c.region()) regionsProcessed++; else filesProcessed++;
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= 1000) {
                lastLogTime = now;
                logProgress();
            }
        }

        private void logProgress() {
            progress.accept(new BackupStep.ScanningWorld(
                    regionsProcessed, filesProcessed, lastDiscoveredRegion, lastDiscoveredFile, false,
                    regionsDiscovered, filesDiscovered, failures));
        }

        void logFinal() {
            progress.accept(new BackupStep.ScanningWorld(
                    regionsProcessed, filesProcessed, "", "", true, regionsDiscovered, filesDiscovered, failures));
        }
    }
}
