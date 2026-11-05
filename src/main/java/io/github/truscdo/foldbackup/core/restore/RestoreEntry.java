package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;

/**
 * One path the target world must contain after a restore.
 * <p>
 * Regions carry their grid coordinates and the identity of the whole unit; regular files carry
 * the identity of the file bytes. {@code sourceMetadata} is the metadata recorded when the
 * content was captured and is used to skip content reads.
 */
public record RestoreEntry(String path, Kind kind, String contentHash, long sizeUncompressed,
        SourceMetadata sourceMetadata, int regionX, int regionZ) {

    public enum Kind { REGION, FILE }

    public RestoreEntry {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path must not be empty");
        if (kind == null) throw new IllegalArgumentException("kind");
        if (contentHash == null || contentHash.isEmpty()) throw new IllegalArgumentException("contentHash");
        if (sizeUncompressed < 0) throw new IllegalArgumentException("sizeUncompressed");
        sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
    }

    public static RestoreEntry region(String path, int regionX, int regionZ, String contentHash,
            long sizeUncompressed, SourceMetadata sourceMetadata) {
        return new RestoreEntry(path, Kind.REGION, contentHash, sizeUncompressed, sourceMetadata, regionX, regionZ);
    }

    public static RestoreEntry file(String path, String sourceHash, long sizeUncompressed,
            SourceMetadata sourceMetadata) {
        return new RestoreEntry(path, Kind.FILE, sourceHash, sizeUncompressed, sourceMetadata, 0, 0);
    }

    public boolean isRegion() {
        return kind == Kind.REGION;
    }
}
