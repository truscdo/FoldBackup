package io.github.truscdo.foldbackup.core.store;

/** A regular file path and its content-addressed Zstd blob in a snapshot manifest. */
public class FileEntry {
    private String filename;
    private String sourceHash;
    private String blobPath;
    private long sizeCompressed;
    private long sizeUncompressed;
    private SourceMetadata sourceMetadata;

    public FileEntry() {
        sourceMetadata = SourceMetadata.empty();
    }

    public FileEntry(String filename, String sourceHash, String blobPath,
                     long sizeCompressed, long sizeUncompressed) {
        this(filename, sourceHash, blobPath, sizeCompressed, sizeUncompressed, SourceMetadata.empty());
    }

    public FileEntry(String filename, String sourceHash, String blobPath,
                     long sizeCompressed, long sizeUncompressed, SourceMetadata sourceMetadata) {
        this.filename = filename;
        this.sourceHash = sourceHash;
        this.blobPath = blobPath;
        this.sizeCompressed = sizeCompressed;
        this.sizeUncompressed = sizeUncompressed;
        this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

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
