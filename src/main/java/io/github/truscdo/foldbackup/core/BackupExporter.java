package io.github.truscdo.foldbackup.core;

import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        /** Cumulative deduplicated blob space through this snapshot in the deletion chain. */
        public final long cumulativeSpaceBytes;

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, 0);
        }

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, cumulativeSpaceBytes);
        }

        public SnapshotInfo(long epochSeconds, String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this.epochSeconds = epochSeconds;
            this.id = id;
            this.date = date;
            this.regions = regions;
            this.files = files;
            this.compressedBytes = compressedBytes;
            this.uncompressedBytes = uncompressedBytes;
            this.cumulativeSpaceBytes = cumulativeSpaceBytes;
        }
    }

    /**
     * 列出所有可用快照及其摘要信息。
     */
    public List<SnapshotInfo> listSnapshots() {
        List<SnapshotManifest> all = snapshotManager.loadAllSnapshots();
        return toSnapshotInfo(all, Map.of());
    }

    /**
     * 列出快照摘要，并计算去重后的累计链式空间。
     * <p>
     * 链式估算只执行一次全量扫描，结果按 Epoch 秒回填；不会为每个快照重复计算。
     */
    public List<SnapshotInfo> listSnapshotsWithCumulativeSpace() {
        List<SnapshotManifest> all = snapshotManager.loadAllSnapshots();
        Map<Long, Long> cumulativeSpaceByEpoch = new HashMap<>();
        for (SnapshotReclamationEstimator.DeletionEstimate estimate
                : SnapshotReclamationEstimator.evaluateDeletionChain(all)) {
            cumulativeSpaceByEpoch.put(estimate.epochSeconds(), estimate.cumulativeReclaimedBytes());
        }
        return toSnapshotInfo(all, cumulativeSpaceByEpoch);
    }

    private List<SnapshotInfo> toSnapshotInfo(List<SnapshotManifest> snapshots,
                                               Map<Long, Long> cumulativeSpaceByEpoch) {
        List<SnapshotInfo> result = new ArrayList<>(snapshots.size());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (SnapshotManifest sn : snapshots) {
            String date = sdf.format(Date.from(Instant.ofEpochSecond(sn.getEpochSeconds())));
            SnapshotManifest.Summary s = sn.getSummary();
            int regions = (s != null) ? s.getRegionsTotal() : 0;
            int files = (s != null) ? s.getExtraFilesTotal() : 0;
            long compressed = (s != null) ? s.getTotalCompressedBytes() : 0;
            long uncompressed = (s != null) ? s.getTotalUncompressedBytes() : 0;
            result.add(new SnapshotInfo(sn.getEpochSeconds(), sn.getSnapshotId(), date, regions, files,
                    compressed, uncompressed, cumulativeSpaceByEpoch.getOrDefault(sn.getEpochSeconds(), 0L)));
        }
        return result;
    }

    /**
     * 返回 Epoch 严格早于目标快照的候选（归档创建 diff 的合法基础候选），
     * 按 {@link #listSnapshots()} 顺序。
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
