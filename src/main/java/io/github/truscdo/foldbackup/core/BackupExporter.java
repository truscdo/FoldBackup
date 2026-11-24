package io.github.truscdo.foldbackup.core;

import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 在线快照的列表与空间摘要视图。 */
public class BackupExporter {

    private final SnapshotStore snapshotManager;

    public BackupExporter(SnapshotStore snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    // --- 列表 ---

    /**
     * 在 list 命令中显示的单个快照摘要信息。
     */
    public static class SnapshotInfo {
        public final long epochSeconds;
        public final String id;
        public final String date;
        public final int regions;
        public final int files;
        public final long compressedBytes;
        public final long uncompressedBytes;
        /** Unique blob bytes reclaimed immediately if this single snapshot is deleted. */
        public final long uniqueBytes;
        /** Cumulative deduplicated blob space through this snapshot in the deletion chain. */
        public final long cumulativeSpaceBytes;
        /** Whether this snapshot is pinned (protected from cleanup and deletion). */
        public final boolean pinned;

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, 0, 0, false);
        }

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, 0, cumulativeSpaceBytes, false);
        }

        public SnapshotInfo(long epochSeconds, String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this(epochSeconds, id, date, regions, files, compressedBytes, uncompressedBytes, 0, cumulativeSpaceBytes, false);
        }

        public SnapshotInfo(long epochSeconds, String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes, boolean pinned) {
            this(epochSeconds, id, date, regions, files, compressedBytes, uncompressedBytes, 0, cumulativeSpaceBytes, pinned);
        }

        public SnapshotInfo(long epochSeconds, String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long uniqueBytes,
                long cumulativeSpaceBytes, boolean pinned) {
            this.epochSeconds = epochSeconds;
            this.id = id;
            this.date = date;
            this.regions = regions;
            this.files = files;
            this.compressedBytes = compressedBytes;
            this.uncompressedBytes = uncompressedBytes;
            this.uniqueBytes = uniqueBytes;
            this.cumulativeSpaceBytes = cumulativeSpaceBytes;
            this.pinned = pinned;
        }
    }

    /** 快照列表与仓库级空间摘要的组合视图（基于同一次全库账本计算）。 */
    public record SnapshotListView(
            List<SnapshotInfo> snapshots,
            SnapshotReclamationEstimator.RepositorySpaceSummary summary) {
        public SnapshotListView {
            snapshots = List.copyOf(snapshots);
        }
    }

    /**
     * 列出所有可用快照及其摘要信息（包含基于全库账本计算的 {@code uniqueBytes} 与 {@code cumulativeSpaceBytes}）。
     */
    public List<SnapshotInfo> listSnapshots() {
        return listSnapshotsWithSummary().snapshots();
    }

    /**
     * 列出快照摘要，并计算去重后的独占空间与累计链式释放空间。
     */
    public List<SnapshotInfo> listSnapshotsWithCumulativeSpace() {
        return listSnapshotsWithSummary().snapshots();
    }

    /**
     * 基于内存索引与 {@code index.db} 体积一次性计算全部快照的空间指标与仓库级摘要（不扫描 {@code blobs/}）。
     */
    public SnapshotListView listSnapshotsWithSummary() {
        List<SnapshotManifest> all = snapshotManager.loadAllSnapshots();
        long indexBytes = snapshotManager.indexFileSizeBytes();
        SnapshotReclamationEstimator.RepositorySpaceAnalysis analysis =
                SnapshotReclamationEstimator.analyze(all, indexBytes);
        return new SnapshotListView(toSnapshotInfo(all, analysis), analysis.summary());
    }

    /**
     * 预估删除指定快照子集可释放的空间（基于全库存活快照账本）。
     */
    public SnapshotReclamationEstimator.SubsetDeletionEstimate estimateDeletion(Collection<Long> targetEpochs) {
        return SnapshotReclamationEstimator.estimateSubsetDeletion(
                snapshotManager.loadAllSnapshots(), targetEpochs, snapshotManager.indexFileSizeBytes());
    }

    private List<SnapshotInfo> toSnapshotInfo(List<SnapshotManifest> snapshots,
                                               SnapshotReclamationEstimator.RepositorySpaceAnalysis analysis) {
        List<SnapshotInfo> result = new ArrayList<>(snapshots.size());
        for (SnapshotManifest sn : snapshots) {
            String date = FormatUtil.formatEpoch(sn.getEpochSeconds());
            SnapshotManifest.Summary s = sn.getSummary();
            int regions = (s != null) ? s.getRegionsTotal() : 0;
            int files = (s != null) ? s.getExtraFilesTotal() : 0;
            long compressed = (s != null) ? s.getTotalCompressedBytes() : 0;
            long uncompressed = (s != null) ? s.getTotalUncompressedBytes() : 0;
            SnapshotReclamationEstimator.SnapshotSpace space = analysis.spaceFor(sn.getEpochSeconds());
            long uniqueBytes = space != null ? space.uniqueBytes() : 0L;
            long reclaimBytes = space != null ? space.reclaimBytes() : 0L;
            result.add(new SnapshotInfo(sn.getEpochSeconds(), sn.getSnapshotId(), date, regions, files,
                    compressed, uncompressed, uniqueBytes, reclaimBytes, sn.isPinned()));
        }
        return result;
    }

    /**
     * 返回 Epoch 严格早于目标快照的候选（归档创建 diff 的合法基础候选），
     * 按 {@link #listSnapshots()} 顺序；{@code uniqueBytes} 仍基于全库全部存活快照计算。
     */
    public List<SnapshotInfo> diffBaseCandidates(long targetEpochSeconds) {
        List<SnapshotInfo> candidates = new ArrayList<>();
        for (SnapshotInfo sn : listSnapshots()) {
            if (sn.epochSeconds < targetEpochSeconds) {
                candidates.add(sn);
            }
        }
        return candidates;
    }
}
