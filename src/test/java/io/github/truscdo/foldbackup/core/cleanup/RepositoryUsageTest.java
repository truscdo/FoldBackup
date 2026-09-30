package io.github.truscdo.foldbackup.core.cleanup;

import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryUsageTest {
    @TempDir Path root;

    @Test
    void countsBlobAndIndexFilesFromFilesystemAndLeavesUnmanagedFilesOut() throws Exception {
        Files.createDirectories(root.resolve("blobs/aa"));
        Files.write(root.resolve("blobs/aa/blob.zst"), new byte[7]);
        Files.write(root.resolve("index.db"), new byte[11]);
        Files.write(root.resolve("notes.log"), new byte[101]);

        SnapshotManifest snapshot = snapshot("blobs/aa/blob.zst", 7);
        RepositoryUsage usage = RepositoryUsage.read(root, List.of(snapshot));

        assertEquals(18L, usage.totalBytes());
        assertEquals(11L, usage.indexBytes());
        assertTrue(usage.usableBytes() >= 0);
    }

    @Test
    void missingReferencedBlobFailsClosed() throws Exception {
        SnapshotManifest snapshot = snapshot("blobs/missing.zst", 1);
        IOException error = assertThrows(IOException.class,
                () -> RepositoryUsage.read(root, List.of(snapshot)));
        assertTrue(error.getMessage().contains("Missing referenced blob"));
    }

    @Test
    void referencedBlobSizeMustMatchSnapshotMetadata() throws Exception {
        Files.createDirectories(root.resolve("blobs/aa"));
        Files.write(root.resolve("blobs/aa/blob.zst"), new byte[7]);

        IOException error = assertThrows(IOException.class,
                () -> RepositoryUsage.read(root, List.of(snapshot("blobs/aa/blob.zst", 6))));
        assertTrue(error.getMessage().contains("Blob size differs from snapshot metadata"));
    }

    @Test
    void unreadableNonRegularIndexFailsClosed() throws Exception {
        Files.createDirectory(root.resolve("index.db"));
        IOException error = assertThrows(IOException.class,
                () -> RepositoryUsage.read(root, List.of()));
        assertTrue(error.getMessage().contains("Not a regular index file"));
    }

    private static SnapshotManifest snapshot(String blobPath, long compressedSize) {
        SnapshotManifest snapshot = new SnapshotManifest("S-20260901_000000", 1, "world");
        snapshot.addExtraFile(new FileEntry("level.dat", "a".repeat(32), blobPath, compressedSize, 1));
        return snapshot;
    }
}
