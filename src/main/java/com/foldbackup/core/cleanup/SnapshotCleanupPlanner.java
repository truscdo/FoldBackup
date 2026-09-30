package com.foldbackup.core.cleanup;

import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotReclamationEstimator;

import java.util.*;

/**
 * 按最旧优先计算连续删除快照的收益，不修改索引或文件。
 * 调用者须在同一个仓库写会话中取得输入、规划并提交删除。
 * 若与新快照一起提交，snapshots 必须包含新快照（含其复用的 blob）。
 */
public final class SnapshotCleanupPlanner {
    private SnapshotCleanupPlanner() {}

    /** 一步的收益依赖之前的删除；不是该快照独立删除时的收益。 */
    public enum Reason { AGE, CAPACITY, FREE_SPACE }

    public record Deletion(long epochSeconds, String snapshotId, Set<Reason> reasons, long reclaimedBytes,
                           long cumulativeReclaimedBytes) {
        public Deletion { reasons = Set.copyOf(reasons); }
    }

    public record Plan(List<Deletion> deletions, long reclaimedBytes,
                       long remainingBytes, boolean targetSatisfied) {
        public Plan {
            deletions = List.copyOf(deletions);
        }

        public List<Long> snapshotEpochs() {
            return deletions.stream().map(Deletion::epochSeconds).toList();
        }
    }

    /**
     * @param snapshots 全部存活快照；输入顺序不限，按 Epoch 秒排序
     * @param currentBytes 仓库当前总字节数，包括索引、孤儿等不可由本计划直接回收的占用
     * @param requiredReclaimedBytes 本次需要释放的字节数；0 不删除，目标不可达时可选中全部快照
     * @return 预计收益；文件删除失败、索引大小变化等须在 GC 后另行核实
     */
    public static Plan plan(List<SnapshotManifest> snapshots, long currentBytes,
                            long requiredReclaimedBytes) {
        return plan(snapshots, currentBytes, requiredReclaimedBytes, 0, 0, 0);
    }

    /** 时间为 epoch 秒；0 天关闭年龄规则，空间缺口取最大值而非相加。 */
    public static Plan plan(List<SnapshotManifest> snapshots, long currentBytes,
                            long capacityDeficit, long freeSpaceDeficit,
                            long nowSeconds, long maxAgeDays) {
        if (capacityDeficit < 0 || freeSpaceDeficit < 0 || maxAgeDays < 0)
            throw new IllegalArgumentException("Limits must be nonnegative");
        long cutoff = maxAgeDays == 0 ? Long.MIN_VALUE
                : Math.subtractExact(nowSeconds, Math.multiplyExact(maxAgeDays, 86400L));
        long requiredReclaimedBytes = Math.max(capacityDeficit, freeSpaceDeficit);

        if (currentBytes < 0)
            throw new IllegalArgumentException("Byte counts must be nonnegative");
        List<SnapshotReclamationEstimator.DeletionEstimate> chain =
                SnapshotReclamationEstimator.evaluateDeletionChain(snapshots);
        long referencedBytes = chain.isEmpty() ? 0
                : chain.getLast().cumulativeReclaimedBytes();
        if (referencedBytes > currentBytes)
            throw new IllegalArgumentException("Referenced blobs exceed current repository size");
        Map<Long, SnapshotManifest> snapshotsByEpoch = new HashMap<>();
        for (SnapshotManifest snapshot : snapshots)
            snapshotsByEpoch.put(snapshot.getEpochSeconds(), snapshot);

        List<Deletion> deletions = new ArrayList<>();
        long reclaimed = 0;
        for (SnapshotReclamationEstimator.DeletionEstimate estimate : chain) {
            SnapshotManifest snapshot = snapshotsByEpoch.get(estimate.epochSeconds());
            boolean expired = maxAgeDays > 0 && snapshot.getEpochSeconds() < cutoff;
            if (!expired && reclaimed >= requiredReclaimedBytes) break;
            Set<Reason> reasons = EnumSet.noneOf(Reason.class);
            if (expired) reasons.add(Reason.AGE);
            if (reclaimed < capacityDeficit) reasons.add(Reason.CAPACITY);
            if (reclaimed < freeSpaceDeficit) reasons.add(Reason.FREE_SPACE);
            deletions.add(new Deletion(estimate.epochSeconds(), estimate.snapshotId(), reasons,
                    estimate.reclaimedBytes(),
                    estimate.cumulativeReclaimedBytes()));
            reclaimed = estimate.cumulativeReclaimedBytes();
        }
        return new Plan(deletions, reclaimed, currentBytes - reclaimed,
                reclaimed >= requiredReclaimedBytes);
    }

}
