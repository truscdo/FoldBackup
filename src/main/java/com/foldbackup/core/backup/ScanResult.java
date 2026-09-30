package com.foldbackup.core.backup;

import com.foldbackup.core.store.FileEntry;
import com.foldbackup.core.store.RegionEntry;
import com.foldbackup.core.store.SnapshotManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a world scan performed by {@link RegionProcessor}.
 * <p>
 * Contains the lists of regions and extra files discovered during scanning,
 * along with change-tracking counters and size statistics.
 */
public class ScanResult {
    /** Relative path of the file that produced this (partial) result. */
    String path;
    /**
     * Whether this partial result came from a region task rather than a plain file.
     */
    boolean regionTask;

    private final List<RegionEntry> regions = new ArrayList<>();
    private final List<FileEntry> files = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    int unchangedRegions = 0;
    int changedRegions = 0;
    int unchangedFiles = 0;
    int changedFiles = 0;
    long newBytes = 0;
    long totalCompressedBytes = 0;
    long totalUncompressedBytes = 0;

    void addRegion(RegionEntry entry) {
        regions.add(entry);
    }

    void addFile(FileEntry entry) {
        files.add(entry);
    }

    void addError(String path, Exception e) {
        // 使用 e.toString() 而非 e.getMessage()：异常可能没有消息
        // （如无参 EOFException），类名本身就是关键诊断信息。
        errors.add(path + ": " + e);
    }

    void finalizeResult() {
        for (RegionEntry r : regions) {
            totalCompressedBytes += r.getSizeCompressed();
            totalUncompressedBytes += r.getSizeUncompressed();
        }
        for (FileEntry f : files) {
            totalCompressedBytes += f.getSizeCompressed();
            totalUncompressedBytes += f.getSizeUncompressed();
        }
    }

    /**
     * Build a {@link SnapshotManifest.Summary} from this scan result.
     */
    public SnapshotManifest.Summary toSummary() {
        SnapshotManifest.Summary summary = new SnapshotManifest.Summary();
        summary.setRegionsTotal(getRegionCount());
        summary.setRegionsChanged(changedRegions);
        summary.setExtraFilesTotal(getFileCount());
        summary.setExtraFilesChanged(changedFiles);
        summary.setTotalCompressedBytes(totalCompressedBytes);
        summary.setTotalUncompressedBytes(totalUncompressedBytes);
        summary.setNewBlobBytes(newBytes);
        return summary;
    }

    /**
     * Populate a {@link SnapshotManifest} with the regions and files
     * from this scan result, plus its summary.
     *
     * @param manifest the manifest to populate
     * @return the same manifest (for chaining)
     */
    public SnapshotManifest populateManifest(SnapshotManifest manifest) {
        for (RegionEntry r : regions) {
            manifest.addRegion(r);
        }
        for (FileEntry f : files) {
            manifest.addExtraFile(f);
        }
        manifest.setSummary(toSummary());
        return manifest;
    }

    // ── Getters ──

    public List<RegionEntry> getRegions() {
        return regions;
    }

    public List<FileEntry> getFiles() {
        return files;
    }

    public List<String> getErrors() {
        return errors;
    }

    public int getUnchangedRegions() {
        return unchangedRegions;
    }

    public int getChangedRegions() {
        return changedRegions;
    }

    public int getUnchangedFiles() {
        return unchangedFiles;
    }

    public int getChangedFiles() {
        return changedFiles;
    }

    public long getNewBytes() {
        return newBytes;
    }

    public long getTotalCompressedBytes() {
        return totalCompressedBytes;
    }

    public long getTotalUncompressedBytes() {
        return totalUncompressedBytes;
    }

    public int getRegionCount() {
        return regions.size();
    }

    public int getFileCount() {
        return files.size();
    }
}
