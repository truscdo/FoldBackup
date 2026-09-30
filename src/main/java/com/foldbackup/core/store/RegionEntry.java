package com.foldbackup.core.store;

/** A Region path and its content-addressed LRH blob in a snapshot manifest. */
public class RegionEntry {
    private String filename;
    private String contentHash;
    private String blobPath;
    private long sizeCompressed;
    private long sizeUncompressed;
    private SourceMetadata sourceMetadata;

    public RegionEntry() {
        sourceMetadata = SourceMetadata.empty();
    }

    public RegionEntry(String filename, String contentHash, String blobPath,
                       long sizeCompressed, long sizeUncompressed) {
        this(filename, contentHash, blobPath, sizeCompressed, sizeUncompressed, SourceMetadata.empty());
    }

    public RegionEntry(String filename, String contentHash, String blobPath,
                       long sizeCompressed, long sizeUncompressed,
                       SourceMetadata sourceMetadata) {
        this.filename = filename;
        this.contentHash = contentHash;
        this.blobPath = blobPath;
        this.sizeCompressed = sizeCompressed;
        this.sizeUncompressed = sizeUncompressed;
        this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getBlobPath() { return blobPath; }
    public void setBlobPath(String blobPath) { this.blobPath = blobPath; }

    public long getSizeCompressed() { return sizeCompressed; }
    public void setSizeCompressed(long sizeCompressed) { this.sizeCompressed = sizeCompressed; }

    public long getSizeUncompressed() { return sizeUncompressed; }
    public void setSizeUncompressed(long sizeUncompressed) { this.sizeUncompressed = sizeUncompressed; }

    public SourceMetadata getSourceMetadata() { return sourceMetadata; }
    public void setSourceMetadata(SourceMetadata sourceMetadata) {
        this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
    }
}
