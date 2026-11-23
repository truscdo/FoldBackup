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

    /** Tag marking a snapshot as pinned (exempt from automatic cleanup and manual deletion). */
    public static final String PINNED_TAG = "pinned";

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

    public boolean isPinned() {
        return tags != null && tags.contains(PINNED_TAG);
    }

    public void setPinned(boolean pinned) {
        if (pinned) {
            if (tags == null) {
                tags = new ArrayList<>();
            } else if (tags.contains(PINNED_TAG)) {
                return;
            } else {
                tags = new ArrayList<>(tags);
            }
            tags.add(PINNED_TAG);
        } else if (tags != null && tags.contains(PINNED_TAG)) {
            tags = new ArrayList<>(tags);
            tags.removeIf(PINNED_TAG::equals);
        }
    }

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

        /** 汇总全量条目数量与字节，并将增量统计（changed/new）设为 {@code 0}。 */
        public static Summary summarize(List<RegionEntry> regions, List<FileEntry> files) {
            return summarize(regions, files, 0, 0, 0L);
        }

        /** 汇总条目数量、字节及增量统计；增量字段必须全为非负值。 */
        public static Summary summarize(List<RegionEntry> regions, List<FileEntry> files,
                int regionsChanged, int extraFilesChanged, long newBlobBytes) {
            if (regionsChanged < 0 || extraFilesChanged < 0 || newBlobBytes < 0) {
                throw new IllegalArgumentException("Delta statistics must be non-negative");
            }
            long compressed = 0;
            long uncompressed = 0;
            if (regions != null) {
                for (RegionEntry r : regions) {
                    compressed = addNonNegativeSize(compressed, r.getSizeCompressed(), "region compressed size");
                    uncompressed = addNonNegativeSize(uncompressed, r.getSizeUncompressed(), "region uncompressed size");
                }
            }
            if (files != null) {
                for (FileEntry f : files) {
                    compressed = addNonNegativeSize(compressed, f.getSizeCompressed(), "file compressed size");
                    uncompressed = addNonNegativeSize(uncompressed, f.getSizeUncompressed(), "file uncompressed size");
                }
            }
            Summary summary = new Summary();
            summary.setRegionsTotal(regions == null ? 0 : regions.size());
            summary.setRegionsChanged(regionsChanged);
            summary.setExtraFilesTotal(files == null ? 0 : files.size());
            summary.setExtraFilesChanged(extraFilesChanged);
            summary.setTotalCompressedBytes(compressed);
            summary.setTotalUncompressedBytes(uncompressed);
            summary.setNewBlobBytes(newBlobBytes);
            return summary;
        }

        private static long addNonNegativeSize(long total, long size, String label) {
            if (size < 0) {
                throw new IllegalArgumentException(label + " must be non-negative: " + size);
            }
            try {
                return Math.addExact(total, size);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(label + " total overflow", e);
            }
        }

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
