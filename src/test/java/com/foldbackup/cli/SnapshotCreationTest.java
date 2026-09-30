package com.foldbackup.cli;

import com.foldbackup.core.BackupExporter;
import com.foldbackup.core.ProgressEvent;
import com.foldbackup.core.backup.RegionProcessor;
import com.foldbackup.core.format.AnvilTestSupport;
import com.foldbackup.core.format.ChunkData;
import com.foldbackup.core.format.Region;
import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCreationTest {
    @TempDir Path temp;

    @Test
    void createsNewStoreAndExportsWorldWithProgress() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        Files.createDirectories(world.resolve("region"));
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] {1, 2, 3}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);
        Path root = temp.resolve("new-store");
        var events = new ArrayList<ProgressEvent>();
        var snapshot = SnapshotCreation.create(world, root, 1, events::add);
        assertEquals("world", snapshot.getWorldName());
        assertEquals(1, snapshot.getRegions().size());
        assertEquals(1, snapshot.getExtraFiles().size());
        var store = new SnapshotStore(root);
        var exporter = new BackupExporter(store, new BlobStore(root, 6), 6);
        Path output = temp.resolve("export");
        var restoreEvents = new ArrayList<ProgressEvent>();
        exporter.exportWithProgress(snapshot.getEpochSeconds(), output, BackupExporter.Format.MCA, false,
                2, restoreEvents::add);
        assertEquals("world metadata", Files.readString(output.resolve("level.dat")));
        assertTrue(Files.exists(output.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(output.resolve("session.lock")));
        assertEquals("Publishing world", restoreEvents.getLast().phase());
        assertEquals("Scanning world", events.getFirst().phase());
        assertEquals("Backup complete", events.getLast().phase());
        assertEquals(1, events.getLast().completed());
        assertEquals(1, events.getLast().total());
        assertTrue(events.getLast().item().contains(snapshot.getSnapshotId()));

        Path zip = exporter.exportWithProgress(snapshot.getEpochSeconds(), temp.resolve("packed"),
                BackupExporter.Format.MCA, true, 2, null);
        assertFalse(Files.exists(temp.resolve("packed")));
        try (var archive = new java.util.zip.ZipFile(zip.toFile())) {
            assertNotNull(archive.getEntry("level.dat"));
            assertNotNull(archive.getEntry("region/r.0.0.mca"));
        }
    }

    @Test
    void corruptRegionDoesNotPublishAnyWorldFiles() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] {1, 2, 3}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);
        Path root = temp.resolve("store");
        var snapshot = SnapshotCreation.create(world, root, 1, null);
        Files.writeString(root.resolve(snapshot.getRegions().getFirst().getBlobPath()), "corrupt");
        Path output = Files.createDirectory(temp.resolve("export"));
        Files.writeString(output.resolve("existing.txt"), "keep");

        var exporter = new BackupExporter(new SnapshotStore(root), new BlobStore(root, 6), 6);
        assertThrows(IOException.class, () -> exporter.exportWithProgress(snapshot.getEpochSeconds(), output,
                BackupExporter.Format.MCA, false, 2, null));
        assertEquals("keep", Files.readString(output.resolve("existing.txt")));
        assertFalse(Files.exists(output.resolve("level.dat")));
        assertFalse(Files.exists(output.resolve("region/r.0.0.mca")));
    }

    @Test
    void appendsWithoutRetentionAndReusesLatestMetadata() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "unchanged");
        Path root = temp.resolve("store");
        var store = new SnapshotStore(root);
        var blobs = new BlobStore(root, 6);
        try (var ignored = store.beginWrite()) {
            var scan = new RegionProcessor(store::getIndex, blobs, 6).scanWorld(world, null);
            var previous = new SnapshotManifest(SnapshotStore.generateSnapshotId(1000), 1000, "world");
            scan.populateManifest(previous);
            store.saveSnapshot(previous);
        }
        var snapshot = SnapshotCreation.create(world, root, 1, null);
        var refreshed = new SnapshotStore(root);
        assertNotNull(refreshed.loadSnapshot(1000));
        assertEquals(0, snapshot.getSummary().getNewBlobBytes());
        assertEquals(0, snapshot.getSummary().getExtraFilesChanged());
        assertEquals(refreshed.loadSnapshot(1000).getExtraFiles().getFirst().getBlobPath(),
                snapshot.getExtraFiles().getFirst().getBlobPath());
    }

    @Test
    void lockedWorldAndMissingWorldDoNotCreateRepository() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Path root = temp.resolve("store");
        try (var ignored = WorldSaveLock.acquireRoot(world)) {
            assertTrue(assertThrows(IOException.class,
                    () -> SnapshotCreation.create(world, root, 1, null)).getMessage().contains("locked"));
        }
        assertFalse(Files.exists(root));
        assertThrows(IOException.class, () -> SnapshotCreation.create(temp.resolve("missing"), root, 1, null));
        assertFalse(Files.exists(root));
    }

    @Test
    void corruptRegionDoesNotCommitAndReleasesWorldLock() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.createDirectory(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "corrupt");
        Path root = temp.resolve("store");
        assertThrows(IOException.class, () -> SnapshotCreation.create(world, root, 1, null));
        assertNull(new SnapshotStore(root).loadLatestSnapshot());
        try (var ignored = WorldSaveLock.acquireRoot(world)) {
            assertNotNull(ignored);
        }
    }

    @Test
    void cliValidatesOptionsBeforeWritingAndCreatesSnapshot() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "data");
        Path root = temp.resolve("store");
        for (var options : List.of(
                List.of("snapshot", "create"),
                List.of("snapshot", "create", "--world"),
                List.of("snapshot", "create", "--threads", "0"),
                List.of("snapshot", "create", "--threads", "bad"),
                List.of("snapshot", "create", "--threads", "1", "--threads", "2"),
                List.of("snapshot", "create", "--world", world.toString(), "--world", world.toString()),
                List.of("snapshot", "create", "--unknown"))) {
            assertThrows(IOException.class, () -> BackupCli.runCreate(root, options));
            assertFalse(Files.exists(root));
        }
        BackupCli.runCreate(root, List.of("snapshot", "create", "--world", world.toString(), "--threads", "1"));
        assertNotNull(new SnapshotStore(root).loadLatestSnapshot());
    }
}
