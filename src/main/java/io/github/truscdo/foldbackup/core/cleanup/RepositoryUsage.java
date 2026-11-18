package io.github.truscdo.foldbackup.core.cleanup;

import io.github.truscdo.foldbackup.core.store.RepositoryLayout;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 写会话内采集：只统计 blobs/、index.db 和 index.db.tmp，不跟随符号链接。 */
public record RepositoryUsage(long totalBytes, long indexBytes, long usableBytes) {

    public static RepositoryUsage read(Path root, List<SnapshotManifest> snapshots) throws IOException {
        Map<String, Long> expectedBlobSizes;
        try {
            expectedBlobSizes = SnapshotReclamationEstimator.collectBlobSizes(snapshots);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        Set<String> foundReferencedBlobs = new HashSet<>();
        long total = 0;
        long index = 0;
        try {
            Path blobs = root.resolve(RepositoryLayout.BLOBS_DIR);
            if (!Files.notExists(blobs, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(blobs)) throw new IOException("Symbolic blob directory: " + blobs);
                try (var paths = Files.walk(blobs)) {
                    for (Path path : (Iterable<Path>) paths::iterator) {
                        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (attr.isDirectory()) continue;
                        if (!attr.isRegularFile()) throw new IOException("Not a regular blob: " + path);
                        String relativePath = root.relativize(path).toString().replace('\\', '/');
                        Long expectedSize = expectedBlobSizes.get(relativePath);
                        if (expectedSize != null) {
                            if (expectedSize != attr.size())
                                throw new IOException("Blob size differs from snapshot metadata: " + relativePath);
                            foundReferencedBlobs.add(relativePath);
                        }
                        total = Math.addExact(total, attr.size());
                    }
                }
            }
            for (String name : List.of(RepositoryLayout.INDEX_FILE, RepositoryLayout.INDEX_TMP_FILE)) {
                Path path = root.resolve(name);
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attr.isRegularFile()) throw new IOException("Not a regular index file: " + path);
                total = Math.addExact(total, attr.size());
                if (name.equals(RepositoryLayout.INDEX_FILE)) index = attr.size();
            }
            for (String path : expectedBlobSizes.keySet())
                if (!foundReferencedBlobs.contains(path)) throw new IOException("Missing referenced blob: " + path);
            return new RepositoryUsage(total, index, Files.getFileStore(root).getUsableSpace());
        } catch (ArithmeticException | UncheckedIOException e) {
            throw new IOException("Cannot measure repository usage", e);
        }
    }
}
