package io.github.truscdo.foldbackup.core.store;

import java.util.ArrayList;
import java.util.List;

/**
 * A snapshot manifest describing one backup point in time.
 * <p>
 * Stored as a snapshot record in {@code index.db}.
 * The Epoch seconds field is the internal snapshot identity; {@code snapshotId}
 * is the user-facing label.
 */
public class SnapshotManifest {

    private String snapshotId;
    /** Unix epoch timestamp in seconds; this is the internal snapshot identity. */
    private long epochSeconds;
    private String worldName;
    private String minecraftVersion;
    private long worldVersion;
    private List<String> tags;
    private List<RegionEntry> regions;
    private List<FileEntry> extraFiles;
    private Summary summary;

    public SnapshotManifest() {
    }

    public SnapshotManifest(String snapshotId, long timeSeconds, String worldName) {
        this.snapshotId = snapshotId;
        this.epochSeconds = timeSeconds;
        this.worldName = worldName;
        this.tags = new ArrayList<>();
        this.regions = new ArrayList<>();
        this.extraFiles = new ArrayList<>();
        this.summary = new Summary();
    }

    /** 深拷贝可变清单，供索引保存和查询使用。 */
    public SnapshotManifest copy() {
        SnapshotManifest copy = new SnapshotManifest(snapshotId, epochSeconds, worldName);
        copy.minecraftVersion = minecraftVersion;
        copy.worldVersion = worldVersion;
        copy.tags = tags == null ? null : new ArrayList<>(tags);
        copy.regions = regions == null ? null : new ArrayList<>(regions.stream()
                .map(r -> new RegionEntry(r.getFilename(), r.getContentHash(), r.getBlobPath(),
                        r.getSizeCompressed(), r.getSizeUncompressed(), r.getSourceMetadata())).toList());
        copy.extraFiles = extraFiles == null ? null : new ArrayList<>(extraFiles.stream()
                .map(f -> new FileEntry(f.getFilename(), f.getSourceHash(), f.getBlobPath(),
                        f.getSizeCompressed(), f.getSizeUncompressed(), f.getSourceMetadata())).toList());
        if (summary == null) {
            copy.summary = null;
        } else {
            copy.summary.setRegionsTotal(summary.getRegionsTotal());
            copy.summary.setRegionsChanged(summary.getRegionsChanged());
            copy.summary.setExtraFilesTotal(summary.getExtraFilesTotal());
            copy.summary.setExtraFilesChanged(summary.getExtraFilesChanged());
            copy.summary.setTotalCompressedBytes(summary.getTotalCompressedBytes());
            copy.summary.setTotalUncompressedBytes(summary.getTotalUncompressedBytes());
            copy.summary.setNewBlobBytes(summary.getNewBlobBytes());
        }
        return copy;
    }

    // --- Getters / Setters ---

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    /** Returns the snapshot identity as Unix epoch seconds. */
    public long getEpochSeconds() { return epochSeconds; }
    /** Sets the snapshot identity as Unix epoch seconds. */
    public void setEpochSeconds(long epochSeconds) { this.epochSeconds = epochSeconds; }

    /** @deprecated Use {@link #getEpochSeconds()} for the snapshot identity. */
    @Deprecated(forRemoval = false)
    public long getTime() { return getEpochSeconds(); }
    /** @deprecated Use {@link #setEpochSeconds(long)} for the snapshot identity. */
    @Deprecated(forRemoval = false)
    public void setTime(long timeSeconds) { setEpochSeconds(timeSeconds); }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }

    public long getWorldVersion() { return worldVersion; }
    public void setWorldVersion(long worldVersion) { this.worldVersion = worldVersion; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<RegionEntry> getRegions() { return regions; }
    public void setRegions(List<RegionEntry> regions) { this.regions = regions; }

    public List<FileEntry> getExtraFiles() { return extraFiles; }
    public void setExtraFiles(List<FileEntry> extraFiles) { this.extraFiles = extraFiles; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public void addRegion(RegionEntry entry) {
        regions.add(entry);
    }

    public void addExtraFile(FileEntry entry) {
        extraFiles.add(entry);
    }

    /**
     * Summary statistics for a snapshot.
     */
    public static class Summary {
        private int regionsTotal;
        private int regionsChanged;
        private int extraFilesTotal;
        private int extraFilesChanged;
        private long totalCompressedBytes;
        private long totalUncompressedBytes;
        private long newBlobBytes;

        public int getRegionsTotal() { return regionsTotal; }
        public void setRegionsTotal(int v) { regionsTotal = v; }

        public int getRegionsChanged() { return regionsChanged; }
        public void setRegionsChanged(int v) { regionsChanged = v; }

        public int getExtraFilesTotal() { return extraFilesTotal; }
        public void setExtraFilesTotal(int v) { extraFilesTotal = v; }

        public int getExtraFilesChanged() { return extraFilesChanged; }
        public void setExtraFilesChanged(int v) { extraFilesChanged = v; }

        public long getTotalCompressedBytes() { return totalCompressedBytes; }
        public void setTotalCompressedBytes(long v) { totalCompressedBytes = v; }

        public long getTotalUncompressedBytes() { return totalUncompressedBytes; }
        public void setTotalUncompressedBytes(long v) { totalUncompressedBytes = v; }

        public long getNewBlobBytes() { return newBlobBytes; }
        public void setNewBlobBytes(long v) { newBlobBytes = v; }
    }
}
