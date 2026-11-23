package io.github.truscdo.foldbackup.core.store;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.backup.RetentionPolicy;
import io.github.truscdo.foldbackup.core.cleanup.RepositoryUsage;
import io.github.truscdo.foldbackup.core.cleanup.SnapshotCleanupPlanner;
import io.github.truscdo.foldbackup.core.cleanup.WriteSpaceBudget;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages snapshot manifests using a single binary index file
 * ({@code index.db}).
 * <p>
 * On construction, {@link IndexStore} reads the entire index into memory.
 * Queries use the last loaded/committed immutable in-memory index with zero I/O.
 * Each write session acquires a directory lock and reloads the disk index before
 * modifying it, so separate processes cannot overwrite each other's commits.
 * Writes rebuild the full file atomically (tmp + rename) — no append-only
 * complexity, no checkpoint, no compaction needed.
 */
public class SnapshotStore {

    /** 快照 ID 的权威格式正则：{@code S-yyyyMMdd_HHmmss}。 */
    public static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("S-\\d{8}_\\d{6}");
    private static final DateTimeFormatter SNAPSHOT_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private static final LogUtil LOG = LogUtil.getLogger(SnapshotStore.class);

    private final IndexStore indexStore;
    private final Path backupRoot;
    private volatile MetadataIndex metadataIndex;
    private final BlobMaintenance blobMaintenance;
    private final BackupDirectoryLock directoryLock;
    private final long lockTimeoutMillis;
    private final ThreadLocal<Integer> writeDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<SaveResult[]> pendingSaveResult = new ThreadLocal<>();

    public SnapshotStore(Path backupRoot) throws IOException {
        this(backupRoot, new IndexStore(backupRoot.resolve(RepositoryLayout.INDEX_FILE)));
    }

    SnapshotStore(Path backupRoot, IndexStore indexStore) throws IOException {
        this(backupRoot, indexStore, new BlobMaintenance(backupRoot));
    }

    SnapshotStore(Path backupRoot, IndexStore indexStore, BlobMaintenance blobMaintenance) throws IOException {
        this(backupRoot, indexStore, blobMaintenance, BackupDirectoryLock.DEFAULT_TIMEOUT_MILLIS);
    }

    SnapshotStore(Path backupRoot, IndexStore indexStore, BlobMaintenance blobMaintenance,
            long lockTimeoutMillis) throws IOException {
        this.backupRoot = backupRoot;
        this.indexStore = indexStore;
        this.blobMaintenance = blobMaintenance;
        this.lockTimeoutMillis = Math.max(0L, lockTimeoutMillis);
        this.directoryLock = BackupDirectoryLock.forRoot(backupRoot);
        try (WriteSession ignored = beginWrite()) {
            // beginWrite loads under the directory lock.
        }
    }

    /**
     * 加锁后刷新索引；锁须覆盖 Blob 生成、去重查询、索引提交和 GC。
     * 可在同一线程嵌套使用，必须通过 try-with-resources 在原线程关闭。
     * 普通查询读取本实例最近加载/提交的稳定视图，不自动刷新其他进程的提交。
     */
    public WriteSession beginWrite() throws IOException {
        directoryLock.lock(this, lockTimeoutMillis);
        try {
            if (writeDepth.get() == 0) metadataIndex = indexStore.load().freeze();
            writeDepth.set(writeDepth.get() + 1);
            return new WriteSession();
        } catch (IOException | RuntimeException e) {
            try {
                directoryLock.unlock();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public final class WriteSession implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private WriteSession() {}

        @Override
        public void close() throws IOException {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong lock owner");
            if (closed) return;
            closed = true;
            int depth = writeDepth.get() - 1;
            if (depth == 0) writeDepth.remove();
            else writeDepth.set(depth);
            directoryLock.unlock();
        }
    }

    /** Generate a display ID from the snapshot's Unix epoch seconds in the system's local time zone. */
    public static String generateSnapshotId(long epochSeconds) {
        return "S-" + SNAPSHOT_ID_TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
    }

    /** 判断快照 ID 是否符合规定的格式。 */
    public static boolean isValidSnapshotId(String id) {
        return id != null && SNAPSHOT_ID_PATTERN.matcher(id).matches();
    }

    /** Thrown when an epoch resolved in the index has no corresponding loaded manifest. */
    public static final class MissingSnapshotManifestException extends IOException {
        public MissingSnapshotManifestException(String snapshotId) {
            super("Snapshot not found: " + snapshotId);
        }
    }

    /** Thrown when attempting to delete a pinned snapshot without unpinning it first. */
    public static final class SnapshotPinnedException extends IOException {
        private final String snapshotId;

        public SnapshotPinnedException(String snapshotId) {
            super("Snapshot is pinned: " + snapshotId + " (unpin it before deleting)");
            this.snapshotId = snapshotId;
        }

        public String snapshotId() {
            return snapshotId;
        }
    }

    /** Snapshot save outcome carrying soft-quota capacity overrun details caused by pinned snapshots. */
    public record SaveResult(long capacityOverLimitBytes, int pinnedSnapshots) {
        public static final SaveResult NONE = new SaveResult(0L, 0);
    }

    /** Resolve a user-facing snapshot ID to its unique internal epoch identity. */
    public long resolveEpoch(String snapshotId) throws IOException {
        if (!isValidSnapshotId(snapshotId)) throw new IOException("Invalid snapshot ID: " + snapshotId);
        var epoch = metadataIndex.findEpochBySnapshotId(snapshotId);
        if (epoch.isEmpty()) throw new IOException("Snapshot not found: " + snapshotId);
        return epoch.getAsLong();
    }

    /** Resolves {@code snapshotId} and returns its non-null manifest. */
    public SnapshotManifest requireSnapshot(String snapshotId) throws IOException {
        return requireSnapshot(resolveEpoch(snapshotId), snapshotId);
    }

    /** Loads the manifest for a resolved epoch and fails if absent. */
    public SnapshotManifest requireSnapshot(long epochSeconds, String snapshotId) throws IOException {
        SnapshotManifest manifest = loadSnapshot(epochSeconds);
        if (manifest == null) throw new MissingSnapshotManifestException(snapshotId);
        return manifest;
    }

    // ──────────────────────────────────────────────
    // Writes
    // ──────────────────────────────────────────────

    /** 将快照及其条目 metadata 写入索引，并原子提交索引文件。 */
    public void saveSnapshot(SnapshotManifest manifest) throws IOException {
        saveSnapshot(manifest, null);
    }

    /** 在副本上新增快照并应用保留策略，一次提交后回收 blob。 */
    public void saveSnapshot(SnapshotManifest manifest, RetentionPolicy retentionPolicy) throws IOException {
        try (WriteSession ignored = beginWrite()) {
            SaveResult result = saveSnapshotLocked(manifest.copy(), retentionPolicy);
            SaveResult[] holder = pendingSaveResult.get();
            if (holder != null) {
                holder[0] = result;
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("snapshot metadata: " + e.getMessage(), e);
        }
    }

    /** 在副本上新增快照并应用保留策略，返回软配额超额告警信息（若有）。 */
    public SaveResult saveSnapshotWithResult(SnapshotManifest manifest, RetentionPolicy retentionPolicy)
            throws IOException {
        SaveResult[] holder = new SaveResult[] { SaveResult.NONE };
        SaveResult[] previous = pendingSaveResult.get();
        pendingSaveResult.set(holder);
        try {
            saveSnapshot(manifest, retentionPolicy);
            return holder[0];
        } finally {
            if (previous == null) {
                pendingSaveResult.remove();
            } else {
                pendingSaveResult.set(previous);
            }
        }
    }

    /**
     * 更新快照的保留状态（幂等）；状态未变时跳过写盘并返回 {@code false}。
     */
    public boolean setPinned(long epochSeconds, boolean pinned) throws IOException {
        try (WriteSession ignored = beginWrite()) {
            SnapshotManifest existing = metadataIndex.loadSnapshot(epochSeconds);
            if (existing == null) {
                throw new MissingSnapshotManifestException(String.valueOf(epochSeconds));
            }
            MetadataIndex next = metadataIndex.copy();
            if (!next.setSnapshotPinned(epochSeconds, pinned)) {
                return false;
            }
            commit(next, -1L);
            return true;
        }
    }

    private SaveResult saveSnapshotLocked(SnapshotManifest manifest, RetentionPolicy retentionPolicy) throws IOException {
        if (!isValidSnapshotId(manifest.getSnapshotId()))
            throw new IOException("Invalid snapshot ID: " + manifest.getSnapshotId());
        if (metadataIndex.loadSnapshot(manifest.getEpochSeconds()) != null)
            throw new IOException("Snapshot epoch already exists: " + manifest.getEpochSeconds());
        if (metadataIndex.findEpochBySnapshotId(manifest.getSnapshotId()).isPresent())
            throw new IOException("Snapshot ID already exists: " + manifest.getSnapshotId());
        MetadataIndex next = metadataIndex.copy();
        // 1. Convert all entries to EntryMetadata
        List<EntryMetadata> allEntries = new ArrayList<>();
        if (manifest.getRegions() != null) {
            for (RegionEntry re : manifest.getRegions()) {
                allEntries.add(new EntryMetadata(
                        EntryMetadata.Type.REGION,
                        re.getFilename(),
                        re.getContentHash(),
                        re.getBlobPath(),
                        re.getSizeCompressed(),
                        re.getSizeUncompressed(),
                        re.getSourceMetadata()));
            }
        }
        if (manifest.getExtraFiles() != null) {
            for (FileEntry fe : manifest.getExtraFiles()) {
                allEntries.add(new EntryMetadata(
                        EntryMetadata.Type.FILE,
                        fe.getFilename(),
                        fe.getSourceHash(),
                        fe.getBlobPath(),
                        fe.getSizeCompressed(),
                        fe.getSizeUncompressed(),
                        fe.getSourceMetadata()));
            }
        }

        // 2. Collect metadata hashes and add metadata to the staged copy
        List<String> entryHashes = new ArrayList<>(allEntries.size());
        for (EntryMetadata entry : allEntries) {
            String metaHash = entry.getMetadataHash();
            entryHashes.add(metaHash);

            if (next.getMetadata(metaHash) == null) {
                next.addMetadata(entry);
            }
        }

        // 3. Add snapshot and apply retention to the staged copy
        next.addSnapshot(manifest, entryHashes);
        // 清理崩溃遗留的 index.db.tmp，避免被 RepositoryUsage 计入 plannedBytes 导致保留策略静默过删。
        long discardedTempBytes = indexStore.discardTemporary();
        if (discardedTempBytes > 0) {
            LOG.info("Discarded stale temporary index ({} bytes) before retention planning", discardedTempBytes);
        }
        CleanupAssessment cleanup = planCleanup(next, retentionPolicy);
        SnapshotCleanupPlanner.Plan plan = cleanup != null ? cleanup.plan() : null;
        int pinnedCount = countPinnedSnapshots(next);
        List<Long> expiredEpochs = List.of();
        boolean allowCapacityOverLimit = false;
        if (plan != null) {
            SnapshotCleanupPlanner.Deletion newSnapshotDeletion = findDeletion(plan, manifest.getEpochSeconds());
            if (newSnapshotDeletion != null) {
                if (pinnedCount == 0 || newSnapshotDeletion.reasons().contains(SnapshotCleanupPlanner.Reason.AGE)) {
                    throw new IOException("Backup not committed: new snapshot cannot fit retention limits: "
                            + manifest.getSnapshotId());
                }
                long reclaimedWithoutNew = reclaimedBytesExcluding(plan, manifest.getEpochSeconds());
                long estimatedFreeAfterOldDeletions = Math.addExact(cleanup.freeAfterCommit(), reclaimedWithoutNew);
                if (retentionPolicy.freeSpaceDeficit(estimatedFreeAfterOldDeletions) > 0) {
                    throw new IOException("Backup not committed: insufficient free space even after deleting all "
                            + "unpinned snapshots (" + pinnedCount + " pinned snapshot(s) protected): "
                            + manifest.getSnapshotId());
                }
                expiredEpochs = plan.snapshotEpochs().stream()
                        .filter(epoch -> epoch != manifest.getEpochSeconds())
                        .toList();
                allowCapacityOverLimit = true;
            } else {
                expiredEpochs = plan.snapshotEpochs();
                if (!plan.targetSatisfied() && pinnedCount > 0) {
                    long estimatedFreeAfterDeletions = Math.addExact(cleanup.freeAfterCommit(), plan.reclaimedBytes());
                    if (retentionPolicy.freeSpaceDeficit(estimatedFreeAfterDeletions) > 0) {
                        throw new IOException("Backup not committed: insufficient free space even after deleting all "
                                + "unpinned snapshots (" + pinnedCount + " pinned snapshot(s) protected): "
                                + manifest.getSnapshotId());
                    }
                    allowCapacityOverLimit = true;
                }
            }
        }

        // 4. Atomically persist to disk and collect unreferenced blobs
        long precomputedSize = cleanup != null && expiredEpochs.isEmpty() ? cleanup.nextIndexBytes() : -1L;
        SaveResult[] resultHolder = new SaveResult[] { SaveResult.NONE };
        boolean softQuotaAllowed = allowCapacityOverLimit;
        removeAndCommit(next, expiredEpochs, manifest, precomputedSize,
                plan != null ? gc -> resultHolder[0] = verifyCleanup(retentionPolicy, plan, gc, softQuotaAllowed) : null);
        return resultHolder[0];
    }

    private static int countPinnedSnapshots(MetadataIndex index) {
        int count = 0;
        for (SnapshotManifest sn : index.getSnapshots()) {
            if (sn.isPinned()) {
                count++;
            }
        }
        return count;
    }

    private static SnapshotCleanupPlanner.Deletion findDeletion(SnapshotCleanupPlanner.Plan plan, long epochSeconds) {
        for (SnapshotCleanupPlanner.Deletion deletion : plan.deletions()) {
            if (deletion.epochSeconds() == epochSeconds) {
                return deletion;
            }
        }
        return null;
    }

    private static long reclaimedBytesExcluding(SnapshotCleanupPlanner.Plan plan, long excludedEpoch) {
        long reclaimed = 0L;
        for (SnapshotCleanupPlanner.Deletion deletion : plan.deletions()) {
            if (deletion.epochSeconds() != excludedEpoch) {
                reclaimed = deletion.cumulativeReclaimedBytes();
            }
        }
        return reclaimed;
    }

    /** 明确区分快照已提交但回收未完成，调用者不可将其视为未生成备份立即重试。 */
    public static final class CommittedCleanupException extends IOException {
        private final long epochSeconds;
        private final String snapshotId;
        public CommittedCleanupException(long epochSeconds, String snapshotId, Throwable cause) {
            super("Snapshot " + snapshotId + " committed, but cleanup verification failed: " + cause.getMessage(), cause);
            this.epochSeconds = epochSeconds;
            this.snapshotId = snapshotId;
        }
        public long epochSeconds() { return epochSeconds; }
        public String snapshotId() { return snapshotId; }
    }

    /** 写入新 blob 前检查空间；外层写锁应持续覆盖后续扫描与提交。 */
    public long prepareBackup() throws IOException {
        try (WriteSession ignored = beginWrite()) {
            long indexBytes = IndexStore.serializedSize(metadataIndex);
            new WriteSpaceBudget(backupRoot, indexBytes, 0).check();
            return indexBytes;
        }
    }

    private record CleanupAssessment(SnapshotCleanupPlanner.Plan plan, long nextIndexBytes, long freeAfterCommit) {}

    private CleanupAssessment planCleanup(MetadataIndex next, RetentionPolicy policy) throws IOException {
        if (policy == null || !policy.isEnabled() || !policy.hasLimits()) return null;
        RepositoryUsage usage = RepositoryUsage.read(backupRoot, next.getSnapshots());
        // 计入本次待提交索引的增长；删除 metadata 后的缩小不作为可保证的 blob 收益。
        long nextIndexBytes = IndexStore.validatedSerializedSize(next);
        long plannedBytes = Math.addExact(usage.totalBytes() - usage.indexBytes(), nextIndexBytes);
        long freeAfterCommit = Math.max(0, usage.usableBytes() - Math.max(0, nextIndexBytes - usage.indexBytes()));
        return new CleanupAssessment(
                policy.plan(next.getSnapshots(), plannedBytes, freeAfterCommit, System.currentTimeMillis() / 1000L),
                nextIndexBytes, freeAfterCommit);
    }

    private SaveResult verifyCleanup(RetentionPolicy policy, SnapshotCleanupPlanner.Plan plan,
                                     BlobMaintenance.GcResult gc, boolean allowCapacityOverLimit) throws IOException {
        if (plan == null) return SaveResult.NONE;
        RepositoryUsage usage = RepositoryUsage.read(backupRoot, metadataIndex.getSnapshots());
        var snapshots = metadataIndex.getSnapshots();
        long oldest = snapshots.stream().mapToLong(SnapshotManifest::getEpochSeconds).min().orElse(0);
        long newest = snapshots.stream().mapToLong(SnapshotManifest::getEpochSeconds).max().orElse(0);
        LOG.info("Cleanup: deletions={}, expectedBytes={}, actualBytes={}, repositoryBytes={}, usableBytes={}, "
                        + "remainingSnapshots={}, oldestEpochSeconds={}, newestEpochSeconds={}, gc={}",
                plan.deletions(), plan.reclaimedBytes(), gc.reclaimedBytes(), usage.totalBytes(),
                usage.usableBytes(), snapshots.size(), oldest, newest, gc);
        long capacityDeficit = policy.capacityDeficit(usage.totalBytes());
        long freeSpaceDeficit = policy.freeSpaceDeficit(usage.usableBytes());
        if (!gc.isClean() || freeSpaceDeficit > 0) {
            throw new IOException("Cleanup incomplete: capacity deficit=" + capacityDeficit
                    + ", free-space deficit=" + freeSpaceDeficit + ", gc=" + gc);
        }
        if (capacityDeficit > 0) {
            if (!allowCapacityOverLimit) {
                throw new IOException("Cleanup incomplete: capacity deficit=" + capacityDeficit
                        + ", free-space deficit=" + freeSpaceDeficit + ", gc=" + gc);
            }
            int pinnedSnapshots = (int) snapshots.stream().filter(SnapshotManifest::isPinned).count();
            LOG.warn("Repository size exceeds maxSize limit by {} bytes after cleanup due to {} pinned snapshot(s); "
                    + "snapshot committed under soft quota", capacityDeficit, pinnedSnapshots);
            return new SaveResult(capacityDeficit, pinnedSnapshots);
        }
        return SaveResult.NONE;
    }

    @FunctionalInterface
    private interface PostCommitCheck {
        void check(BlobMaintenance.GcResult gc) throws IOException;
    }

    /** 提交已暂存的快照删除并运行孤儿 blob GC；提交本身失败直接抛 IOException，已提交后的清理失败按需包装。 */
    private BlobMaintenance.GcResult removeAndCommit(MetadataIndex next, Collection<Long> epochsToRemove,
            SnapshotManifest committedManifest, long precomputedValidatedSize,
            PostCommitCheck postCommit) throws IOException {
        for (long epoch : epochsToRemove) {
            next.removeSnapshot(epoch);
        }
        commit(next, precomputedValidatedSize);
        try {
            BlobMaintenance.GcResult gc = collectGarbageDetailedLocked();
            if (postCommit != null) {
                postCommit.check(gc);
            }
            return gc;
        } catch (IOException | RuntimeException e) {
            if (committedManifest != null) {
                throw new CommittedCleanupException(
                        committedManifest.getEpochSeconds(), committedManifest.getSnapshotId(), e);
            }
            throw e;
        }
    }

    /** 从索引删除一个快照，并回收不再引用的 metadata 与 blob；返回 GC 结果以暴露未清净状态。 */
    public BlobMaintenance.GcResult deleteSnapshot(long epochSeconds) throws IOException {
        return deleteSnapshots(List.of(epochSeconds));
    }

    /** 批量删除快照并执行一次 metadata 与 blob 垃圾回收；返回 GC 结果以暴露未清净状态。 */
    public BlobMaintenance.GcResult deleteSnapshots(Collection<Long> snapshotEpochs) throws IOException {
        try (WriteSession ignored = beginWrite()) {
            for (long epoch : snapshotEpochs) {
                SnapshotManifest manifest = metadataIndex.loadSnapshot(epoch);
                if (manifest != null && manifest.isPinned()) {
                    throw new SnapshotPinnedException(manifest.getSnapshotId());
                }
            }
            return removeAndCommit(metadataIndex.copy(), snapshotEpochs, null, -1L, null);
        }
    }

    /** 只在写盘成功后发布新索引；无索引变化时跳过写盘。 */
    private void commit(MetadataIndex next, long precomputedValidatedSize) throws IOException {
        int preGcMetadataCount = next.getAllMetadata().size();
        gcIndex(next);
        if (next.sameContents(metadataIndex)) {
            return;
        }
        long validatedSize = next.getAllMetadata().size() == preGcMetadataCount ? precomputedValidatedSize : -1L;
        try (var ignored = indexStore.prevalidated(next, validatedSize)) {
            indexStore.save(next);
        }
        metadataIndex = next.freeze();
    }

    /** 按当前已提交索引重试 Blob GC；不写索引，失败文件留待下次重试。 */
    public int collectGarbage() throws IOException {
        try (WriteSession ignored = beginWrite()) {
            return collectGarbageLocked();
        }
    }

    private int collectGarbageLocked() {
        return collectGarbageDetailedLocked().deletedFiles();
    }

    public BlobMaintenance.GcResult collectGarbageDetailed() throws IOException {
        try (WriteSession ignored = beginWrite()) {
            return collectGarbageDetailedLocked();
        }
    }

    private BlobMaintenance.GcResult collectGarbageDetailedLocked() {
        BlobMaintenance.GcResult result = blobMaintenance.deleteOrphansDetailed(metadataIndex.collectAllBlobPaths());
        if (!result.isClean())
            LOG.warn("Blob GC incomplete: {}", result);
        else if (result.deletedFiles() > 0)
            LOG.info("Blob GC: {}", result);
        return result;
    }

    // ──────────────────────────────────────────────
    // Internal GC helpers
    // ──────────────────────────────────────────────

    /** 在待提交副本中删除所有不再被存活快照引用的 metadata。 */
    private static void gcIndex(MetadataIndex index) {
        Set<String> activeMetadataHashes = new HashSet<>();

        for (SnapshotManifest sn : index.getSnapshots()) {
            activeMetadataHashes.addAll(index.getEntryHashes(sn.getEpochSeconds()));
        }

        index.retainOnlyMetadata(activeMetadataHashes);
    }

    // ──────────────────────────────────────────────
    // Reads (all from in-memory index)
    // ──────────────────────────────────────────────

    /** 返回按最新优先排列的全部快照 ID。 */
    public List<String> listSnapshots() {
        List<String> ids = new ArrayList<>();
        for (SnapshotManifest sn : metadataIndex.getSnapshots()) {
            ids.add(sn.getSnapshotId());
        }
        return ids;
    }

    /** 返回内存中的全部快照清单，顺序为最新优先。 */
    public List<SnapshotManifest> loadAllSnapshots() {
        return new ArrayList<>(metadataIndex.getSnapshots());
    }

    /** 按 Epoch 秒返回内存中的快照清单。 */
    public SnapshotManifest loadSnapshot(long epochSeconds) {
        return metadataIndex.loadSnapshot(epochSeconds);
    }

    /** 返回最新快照清单；仓库为空时返回 {@code null}。 */
    public SnapshotManifest loadLatestSnapshot() {
        return metadataIndex.loadLatestSnapshot();
    }

    // ──────────────────────────────────────────────
    // Index access
    // ──────────────────────────────────────────────

    /** 返回当前已提交的只读稳定索引；长期使用者应在每次查询时重新获取。 */
    public MetadataIndex getIndex() {
        return metadataIndex;
    }
}
