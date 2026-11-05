package io.github.truscdo.foldbackup.core.export;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ZIP export: suffix completion, replacement, and the artifacts it leaves behind. */
class SnapshotZipExportTest {
    @TempDir Path temp;
    private Path storeRoot;
    private SnapshotStore store;
    private BlobStore blobs;

    private SnapshotStore createSnapshot() throws Exception {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] {1, 2, 3}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);

        storeRoot = temp.resolve("store");
        SnapshotCreation.create(world, storeRoot, 1, event -> { });
        store = new SnapshotStore(storeRoot);
        blobs = new BlobStore(storeRoot, ArchiveDefaults.EXPORT_LEVEL);
        return store;
    }

    private Path export(String snapshotId, Path output) throws Exception {
        return SnapshotZipExport.export(store, blobs, snapshotId, output, 1, null);
    }

    @Test
    void appendsZipSuffixWhenMissing() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();

        Path result = export(id, temp.resolve("packed"));

        assertEquals(temp.resolve("packed.zip").toAbsolutePath().normalize(), result);
        assertTrue(Files.isRegularFile(result));
        try (var archive = new ZipFile(result.toFile())) {
            assertNotNull(archive.getEntry("level.dat"));
        }
        assertFalse(Files.exists(temp.resolve("packed")), "no world directory is left behind");
    }

    @Test
    void appendsZipSuffixToForeignExtension() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();

        assertEquals(temp.resolve("packed.tar.zip").toAbsolutePath().normalize(),
                export(id, temp.resolve("packed.tar")));
    }

    @Test
    void keepsExistingZipSuffix() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();

        assertEquals(temp.resolve("packed.zip").toAbsolutePath().normalize(),
                export(id, temp.resolve("packed.zip")));
    }

    @Test
    void replacesExistingZip() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();
        Path zip = temp.resolve("out.zip");
        Files.writeString(zip, "stale content");

        assertEquals(zip.toAbsolutePath().normalize(), export(id, zip));
        try (var archive = new ZipFile(zip.toFile())) {
            assertNotNull(archive.getEntry("level.dat"));
        }
    }

    @Test
    void refusesDirectoryTarget() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();
        Path target = Files.createDirectories(temp.resolve("already-a-directory.zip"));

        assertThrows(IOException.class, () -> export(id, target));
        assertTrue(Files.isDirectory(target), "the conflicting directory is left untouched");
    }

    @Test
    void refusesBlankAndRootPaths() throws Exception {
        createSnapshot();

        assertThrows(IOException.class, () -> SnapshotZipExport.resolveTarget(null));
        assertThrows(IOException.class, () -> SnapshotZipExport.resolveTarget(Path.of("")));
        assertThrows(IOException.class, () -> SnapshotZipExport.resolveTarget(temp.getRoot()));
    }

    @Test
    void keepsSourceModifiedTimeInZipEntries() throws Exception {
        Path world = temp.resolve("stamped");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        long modified = 1_600_000_000_000L;
        Files.setLastModifiedTime(world.resolve("level.dat"), FileTime.fromMillis(modified));
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] {1, 2, 3}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);

        storeRoot = temp.resolve("stamped-store");
        SnapshotCreation.create(world, storeRoot, 1, event -> { });
        store = new SnapshotStore(storeRoot);
        blobs = new BlobStore(storeRoot, ArchiveDefaults.EXPORT_LEVEL);
        String id = store.loadLatestSnapshot().getSnapshotId();

        Path zip = export(id, temp.resolve("stamped"));
        try (var archive = new ZipFile(zip.toFile())) {
            ZipEntry entry = archive.getEntry("level.dat");
            assertNotNull(entry);
            assertTrue(Math.abs(entry.getLastModifiedTime().toMillis() - modified) < 2000,
                    "entry time follows the snapshot metadata, was " + entry.getLastModifiedTime());
        }
    }

    @Test
    void removesStagingDirectoryNextToTarget() throws Exception {
        String id = createSnapshot().loadLatestSnapshot().getSnapshotId();
        Path parent = Files.createDirectories(temp.resolve("out"));

        export(id, parent.resolve("world.zip"));

        try (var listing = Files.list(parent)) {
            assertTrue(listing.noneMatch(path -> path.getFileName().toString().contains(".foldbackup-")),
                    "staging directory is removed after a successful export");
        }
    }
}
