package io.github.truscdo.foldbackup.core.cleanup;

import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 写会话内采集：只统计 blobs/、index.db 和 index.db.tmp，不跟随符号链接。 */
public record RepositoryUsage(long totalBytes, long indexBytes, long usableBytes) {

    public static RepositoryUsage read(Path root, List<SnapshotManifest> snapshots) throws IOException {
        Map<String, Long> expectedBlobSizes = new HashMap<>();
        for (SnapshotManifest snapshot : snapshots) {
            if (snapshot.getRegions() != null)
                for (var entry : snapshot.getRegions())
                    addExpectedBlobSize(expectedBlobSizes, entry.getBlobPath(), entry.getSizeCompressed());
            if (snapshot.getExtraFiles() != null)
                for (var entry : snapshot.getExtraFiles())
                    addExpectedBlobSize(expectedBlobSizes, entry.getBlobPath(), entry.getSizeCompressed());
        }

        Set<String> foundReferencedBlobs = new HashSet<>();
        long total = 0;
        long index = 0;
        try {
            Path blobs = root.resolve("blobs");
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
            for (String name : List.of("index.db", "index.db.tmp")) {
                Path path = root.resolve(name);
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attr.isRegularFile()) throw new IOException("Not a regular index file: " + path);
                total = Math.addExact(total, attr.size());
                if (name.equals("index.db")) index = attr.size();
            }
            for (String path : expectedBlobSizes.keySet())
                if (!foundReferencedBlobs.contains(path)) throw new IOException("Missing referenced blob: " + path);
            return new RepositoryUsage(total, index, Files.getFileStore(root).getUsableSpace());
        } catch (ArithmeticException | UncheckedIOException e) {
            throw new IOException("Cannot measure repository usage", e);
        }
    }

    private static void addExpectedBlobSize(Map<String, Long> sizes, String path, long size) throws IOException {
        if (path == null || path.isBlank()) throw new IOException("Missing blob path in snapshot metadata");
        if (size < 0) throw new IOException("Negative blob size in snapshot metadata: " + path);
        Long previous = sizes.putIfAbsent(path, size);
        if (previous != null && previous != size)
            throw new IOException("Conflicting blob sizes in snapshot metadata: " + path);
    }
}
