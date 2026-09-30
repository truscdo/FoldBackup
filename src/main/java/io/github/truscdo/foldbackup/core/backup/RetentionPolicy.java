package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.cleanup.SnapshotCleanupPlanner;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import java.util.List;

/** 容量优先的自动清理配置；天数不是最低保留承诺，标签不提供豁免。 */
public final class RetentionPolicy {
    private boolean enabled = true;
    private long maxSizeBytes;
    private long minFreeSpaceBytes;
    private long maxAgeDays;

    public RetentionPolicy setEnabled(boolean value) { enabled = value; return this; }
    public RetentionPolicy setMaxSizeBytes(long value) {
        maxSizeBytes = nonnegative(value); return this;
    }
    public RetentionPolicy setMinFreeSpaceBytes(long value) {
        minFreeSpaceBytes = nonnegative(value); return this;
    }
    public RetentionPolicy setMaxAgeDays(long value) {
        Math.multiplyExact(nonnegative(value), 86400L);
        maxAgeDays = value; return this;
    }
    public boolean isEnabled() { return enabled; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public long getMinFreeSpaceBytes() { return minFreeSpaceBytes; }
    public long getMaxAgeDays() { return maxAgeDays; }
    public boolean hasLimits() { return maxSizeBytes > 0 || minFreeSpaceBytes > 0 || maxAgeDays > 0; }
    public long capacityDeficit(long bytes) { return maxSizeBytes == 0 ? 0 : Math.max(0, bytes - maxSizeBytes); }
    public long freeSpaceDeficit(long bytes) { return minFreeSpaceBytes == 0 ? 0 : Math.max(0, minFreeSpaceBytes - bytes); }

    public SnapshotCleanupPlanner.Plan plan(List<SnapshotManifest> snapshots,
                                           long bytes, long freeBytes, long nowSeconds) {
        return SnapshotCleanupPlanner.plan(snapshots, bytes,
                enabled ? capacityDeficit(bytes) : 0,
                enabled ? freeSpaceDeficit(freeBytes) : 0, nowSeconds, enabled ? maxAgeDays : 0);
    }

    private static long nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Retention limits must be nonnegative");
        return value;
    }
}
