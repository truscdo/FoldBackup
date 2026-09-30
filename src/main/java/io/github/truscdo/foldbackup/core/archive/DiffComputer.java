package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.util.List;

/** Shared path and files.bin metadata for the streaming archive delta pipeline. */
final class DiffComputer {
    private DiffComputer() {}
    /** "region/r.0.0.mca" → "region"; "DIM-1/region/r.0.0.mca" → "DIM-1/region" */
    static String directoryOf(String filename) {
        int slash = filename.lastIndexOf('/');
        return slash < 0 ? "" : filename.substring(0, slash);
    }

    /** files.bin model; {@code baseEpoch} is Unix epoch seconds. */
    static final class FilesManifest {
        long baseEpoch;
        List<FileDiff> files;

        static final class FileDiff {
            String path;
            String sourceHash;
            long sizeUncompressed;
            SourceMetadata sourceMetadata = SourceMetadata.empty();
            boolean deleted;

            FileDiff() {
            }

            FileDiff(String path, String sourceHash, long sizeUncompressed,
                     SourceMetadata sourceMetadata, boolean deleted) {
                this.path = path;
                this.sourceHash = sourceHash;
                this.sizeUncompressed = sizeUncompressed;
                this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
                this.deleted = deleted;
            }
        }
    }
}
