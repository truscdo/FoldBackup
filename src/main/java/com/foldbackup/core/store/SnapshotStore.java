package com.foldbackup.core.store;

import com.foldbackup.core.LogUtil;
import com.foldbackup.core.backup.RetentionPolicy;
import com.foldbackup.core.cleanup.RepositoryUsage;
import com.foldbackup.core.cleanup.SnapshotCleanupPlanner;
import com.foldbackup.core.cleanup.WriteSpaceBudget;

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

    private static final Pattern SNAPSHOT_ID = Pattern.compile("S-\\d{8}_\\d{6}");
    private static final DateTimeFormatter SNAPSHOT_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private static final String INDEX_FILE = "index.db";
    private static final LogUtil LOG = LogUtil.getLogger(SnapshotStore.class);

    private final IndexStore indexStore;
    private final Path backupRoot;
    private volatile MetadataIndex metadataIndex;
    private final BlobMaintenance blobMaintenance;
    private final BackupDirectoryLock directoryLock;
    private final ThreadLocal<Integer> writeDepth = ThreadLocal.withInitial(() -> 0);

    public SnapshotStore(Path backupRoot) throws IOException {
        this(backupRoot, new IndexStore(backupRoot.resolve(INDEX_FILE)));
    }

    SnapshotStore(Path backupRoot, IndexStore indexStore) throws IOException {
        this(backupRoot, indexStore, new BlobMaintenance(backupRoot));
    }

    SnapshotStore(Path backupRoot, IndexStore indexStore, BlobMaintenance blobMaintenance) throws IOException {
        this.backupRoot = backupRoot;
        this.indexStore = indexStore;
        this.blobMaintenance = blobMaintenance;
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
        directoryLock.lock(this);
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
        return id != null && SNAPSHOT_ID.matcher(id).matches();
    }

    /** Resolve a user-facing snapshot ID to its unique internal epoch identity. */
    public long resolveEpoch(String snapshotId) throws IOException {
        if (!isValidSnapshotId(snapshotId)) throw new IOException("Invalid snapshot ID: " + snapshotId);
        var epoch = metadataIndex.findEpochBySnapshotId(snapshotId);
        if (epoch.isEmpty()) throw new IOException("Snapshot not found: " + snapshotId);
        return epoch.getAsLong();
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
            saveSnapshotLocked(manifest.copy(), retentionPolicy);
        } catch (IllegalArgumentException e) {
            throw new IOException("snapshot metadata: " + e.getMessage(), e);
        }
    }

    private void saveSnapshotLocked(SnapshotManifest manifest, RetentionPolicy retentionPolicy) throws IOException {
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
        SnapshotCleanupPlanner.Plan plan = planCleanup(next, retentionPolicy);
        if (plan != null && plan.snapshotEpochs().contains(manifest.getEpochSeconds()))
            throw new IOException("Backup not committed: new snapshot cannot fit retention limits: "
                    + manifest.getSnapshotId());
        if (plan != null) for (long epoch : plan.snapshotEpochs()) next.removeSnapshot(epoch);

        // 4. Atomically persist to disk
        commit(next);
        try {
            BlobMaintenance.GcResult gc = collectGarbageDetailedLocked();
            verifyCleanup(retentionPolicy, plan, gc);
        } catch (IOException | RuntimeException e) {
            throw new CommittedCleanupException(manifest.getEpochSeconds(), manifest.getSnapshotId(), e);
        }
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

    private SnapshotCleanupPlanner.Plan planCleanup(MetadataIndex next, RetentionPolicy policy) throws IOException {
        if (policy == null || !policy.isEnabled() || !policy.hasLimits()) return null;
        RepositoryUsage usage = RepositoryUsage.read(backupRoot, next.getSnapshots());
        // 计入本次待提交索引的增长；删除 metadata 后的缩小不作为可保证的 blob 收益。
        long nextIndexBytes = IndexStore.serializedSize(next);
        long plannedBytes = Math.addExact(usage.totalBytes() - usage.indexBytes(), nextIndexBytes);
        long freeAfterCommit = Math.max(0, usage.usableBytes() - Math.max(0, nextIndexBytes - usage.indexBytes()));
        return policy.plan(next.getSnapshots(), plannedBytes, freeAfterCommit,
                System.currentTimeMillis() / 1000L);
    }

    private void verifyCleanup(RetentionPolicy policy, SnapshotCleanupPlanner.Plan plan,
                               BlobMaintenance.GcResult gc) throws IOException {
        if (plan == null) return;
        RepositoryUsage usage = RepositoryUsage.read(backupRoot, metadataIndex.getSnapshots());
        var snapshots = metadataIndex.getSnapshots();
        long oldest = snapshots.stream().mapToLong(SnapshotManifest::getEpochSeconds).min().orElse(0);
        long newest = snapshots.stream().mapToLong(SnapshotManifest::getEpochSeconds).max().orElse(0);
        LOG.info("Cleanup: deletions={}, expectedBytes={}, actualBytes={}, repositoryBytes={}, usableBytes={}, "
                        + "remainingSnapshots={}, oldestEpochSeconds={}, newestEpochSeconds={}, gc={}",
                plan.deletions(), plan.reclaimedBytes(), gc.reclaimedBytes(), usage.totalBytes(),
                usage.usableBytes(), snapshots.size(), oldest, newest, gc);
        if (policy.capacityDeficit(usage.totalBytes()) > 0 || policy.freeSpaceDeficit(usage.usableBytes()) > 0
                || !gc.scanComplete() || gc.failedFiles() > 0)
            throw new IOException("Cleanup incomplete: capacity deficit=" + policy.capacityDeficit(usage.totalBytes())
                    + ", free-space deficit=" + policy.freeSpaceDeficit(usage.usableBytes()) + ", gc=" + gc);
    }

    /** 从索引删除一个快照，并回收不再引用的 metadata 与 blob。 */
    public void deleteSnapshot(long epochSeconds) throws IOException {
        deleteSnapshots(List.of(epochSeconds));
    }

    /** 批量删除快照并执行一次 metadata 与 blob 垃圾回收。 */
    public void deleteSnapshots(Collection<Long> snapshotEpochs) throws IOException {
        try (WriteSession ignored = beginWrite()) {
            MetadataIndex next = metadataIndex.copy();
            for (long epoch : snapshotEpochs) {
                next.removeSnapshot(epoch);
            }
            commit(next);
            collectGarbageLocked();
        }
    }

    /** 只在写盘成功后发布新索引；无索引变化时跳过写盘。 */
    private void commit(MetadataIndex next) throws IOException {
        gcIndex(next);
        if (next.sameContents(metadataIndex)) {
            return;
        }
        indexStore.save(next);
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
        if (result.deletedFiles() > 0 || result.failedFiles() > 0 || !result.scanComplete())
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
