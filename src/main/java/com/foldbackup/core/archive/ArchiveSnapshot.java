package com.foldbackup.core.archive;

import com.foldbackup.core.store.SourceMetadata;
import java.util.ArrayList;
import java.util.List;

/** Complete path manifests and content identities embedded in every archive. */
public class ArchiveSnapshot {
    public String type;
    public String sourceSnapshotId;
    public long epoch;
    public long baseEpoch;
    public String world;
    public String minecraftVersion;
    public long worldVersion;
    public List<String> tags = new ArrayList<>();
    public int regionCount;
    public int chunkCount;
    public List<RegionInfo> regions = new ArrayList<>();
    public List<FileInfo> files = new ArrayList<>();

    public static class RegionInfo {
        public String filename;
        public long sizeUncompressed;
        public String contentHash;
        public SourceMetadata sourceMetadata = SourceMetadata.empty();

        public RegionInfo() {}
        public RegionInfo(String filename, long sizeUncompressed, String contentHash,
                          SourceMetadata sourceMetadata) {
            this.filename = filename;
            this.sizeUncompressed = sizeUncompressed;
            this.contentHash = contentHash;
            this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
        }
    }

    public static class FileInfo {
        public String filename;
        public long sizeUncompressed;
        public String sourceHash;
        public SourceMetadata sourceMetadata = SourceMetadata.empty();

        public FileInfo() {}
        public FileInfo(String filename, long sizeUncompressed, String sourceHash,
                        SourceMetadata sourceMetadata) {
            this.filename = filename;
            this.sizeUncompressed = sizeUncompressed;
            this.sourceHash = sourceHash;
            this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
        }
    }

    public boolean isFull() { return "full".equals(type); }
    public boolean isDiff() { return "diff".equals(type); }
}
