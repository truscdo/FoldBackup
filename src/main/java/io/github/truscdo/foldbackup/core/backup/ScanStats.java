package io.github.truscdo.foldbackup.core.backup;

/**
 * Aggregated statistics from a world scan.
 * <p>
 * Groups four related integer counters that are frequently passed together
 * through the backup workflow — eliminating the "data clump" bad smell.
 */
public record ScanStats(int regionCount, int changedRegions,
        int fileCount, int changedFiles) {

    /** 被扫描的条目总数。 */
    public int totalCount() {
        return regionCount + fileCount;
    }

    /** 内容发生变化的条目总数。 */
    public int totalChanged() {
        return changedRegions + changedFiles;
    }

    /** 人类友好的摘要行。 */
    public String format() {
        return String.format("%d regions (%d changed), %d files (%d changed)",
                regionCount, changedRegions, fileCount, changedFiles);
    }
}
