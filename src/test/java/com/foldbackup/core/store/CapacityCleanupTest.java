package com.foldbackup.core.store;

import com.foldbackup.core.backup.*;
import com.foldbackup.core.cleanup.RepositoryUsage;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapacityCleanupTest {
    @TempDir Path root;
    private static final String OLD = "S-20260901_000000";
    private static final String MIDDLE = "S-20260902_000000";
    private static final String NEW = "S-20260903_000000";

    private SnapshotManifest snapshot(String id, long time, String path) throws IOException {
        var sn = new SnapshotManifest(id, time, "world");
        sn.addExtraFile(new FileEntry("level.dat", "a".repeat(32), path, Files.size(root.resolve(path)), 1));
        return sn;
    }

    private String blob(String name, int size) throws IOException {
        Files.createDirectories(root.resolve("blobs"));
        Files.write(root.resolve("blobs/" + name), new byte[size]);
        return "blobs/" + name;
    }

    @Test void stagedNewReferencePreservesSharedBlobAndCommitsBatchOnce() throws Exception {
        class CountingIndex extends IndexStore {
            int saves;
            CountingIndex() { super(root.resolve("index.db")); }
            @Override public void save(MetadataIndex index) throws IOException { saves++; super.save(index); }
        }
        var disk = new CountingIndex();
        var store = new SnapshotStore(root, disk);
        String shared = blob("shared", 8192);
        store.saveSnapshot(snapshot(OLD, 1, shared));
        String unique = blob("unique", 8192);
        store.saveSnapshot(snapshot(MIDDLE, 2, unique));
        int before = disk.saves;
        store.saveSnapshot(snapshot(NEW, 3, shared), new RetentionPolicy().setMaxSizeBytes(10000));
        assertEquals(before + 1, disk.saves);
        assertEquals(List.of(NEW), store.listSnapshots());
        assertTrue(Files.exists(root.resolve(shared)));
        assertFalse(Files.exists(root.resolve(unique)));
        var reloaded = new SnapshotStore(root);
        assertEquals(List.of(NEW), reloaded.listSnapshots());
        assertArrayEquals(new byte[8192], new BlobStore(root, 6).read(
                reloaded.loadSnapshot(reloaded.resolveEpoch(NEW)).getExtraFiles().getFirst().getBlobPath()));
    }

    @Test void prepareBackupDoesNotCleanOrPruneBeforeScanning() throws Exception {
        var store = new SnapshotStore(root);
        store.saveSnapshot(snapshot(OLD, 1, blob("live", 10)));
        blob("orphan.wip", 20000);
        Files.write(root.resolve("index.db.tmp"), new byte[20000]);
        Files.write(root.resolve("unmanaged.wip"), new byte[30000]);
        store.prepareBackup();
        assertEquals(List.of(OLD), store.listSnapshots());
        assertTrue(Files.exists(root.resolve("blobs/orphan.wip")));
        assertTrue(Files.exists(root.resolve("index.db.tmp")));
        assertTrue(Files.exists(root.resolve("unmanaged.wip")));
    }

    @Test void disabledCleanupKeepsHistoryDespiteLimits() throws Exception {
        var store = new SnapshotStore(root);
        store.saveSnapshot(snapshot(OLD, 1, blob("live", 10)));
        store.prepareBackup();
        assertEquals(List.of(OLD), store.listSnapshots());
    }

    @Test void prepareBackupDoesNotApplyRetentionBeforeScanning() throws Exception {
        var store = new SnapshotStore(root);
        store.saveSnapshot(snapshot(OLD, 1, blob("live", 10)));
        store.prepareBackup();
        assertEquals(List.of(OLD), store.listSnapshots());
        assertTrue(Files.exists(root.resolve("blobs/live")));
    }

    @Test void cannotFitNewSnapshotLeavesCommittedIndexAndBlobsIntact() throws Exception {
        var store = new SnapshotStore(root);
        String old = blob("old", 8192);
        store.saveSnapshot(snapshot(OLD, 1, old));
        byte[] index = Files.readAllBytes(root.resolve("index.db"));
        String fresh = blob("new", 8192);
        IOException error = assertThrows(IOException.class, () -> store.saveSnapshot(
                snapshot(NEW, 2, fresh), new RetentionPolicy().setMaxSizeBytes(100)));
        assertTrue(error.getMessage().contains("new snapshot cannot fit"));
        assertArrayEquals(index, Files.readAllBytes(root.resolve("index.db")));
        assertEquals(List.of(OLD), store.listSnapshots());
        assertTrue(Files.exists(root.resolve(old)));
        assertTrue(Files.exists(root.resolve(fresh)));
    }

    @Test void gcFailureAfterCommitReportsCommittedSnapshotAndRetryReclaimsExactBytes() throws Exception {
        class FailingGc extends BlobMaintenance {
            boolean fail = true;
            FailingGc() { super(root); }
            @Override protected boolean deleteOrphan(Path path) throws IOException {
                if (fail) throw new IOException("injected deletion failure");
                return super.deleteOrphan(path);
            }
        }
        var gc = new FailingGc();
        var store = new SnapshotStore(root, new IndexStore(root.resolve("index.db")), gc);
        String old = blob("old", 8192);
        store.saveSnapshot(snapshot(OLD, 1, old));
        String fresh = blob("new", 128);
        var error = assertThrows(SnapshotStore.CommittedCleanupException.class, () -> store.saveSnapshot(
                snapshot(NEW, System.currentTimeMillis() / 1000L, fresh), new RetentionPolicy().setMaxAgeDays(1)));
        assertEquals(NEW, error.snapshotId());
        assertEquals(List.of(NEW), new SnapshotStore(root).listSnapshots());
        assertTrue(Files.exists(root.resolve(old)));
        assertTrue(Files.exists(root.resolve(fresh)));
        var failed = store.collectGarbageDetailed();
        assertEquals(0, failed.reclaimedBytes());
        assertEquals(1, failed.failedFiles());
        gc.fail = false;
        var retried = store.collectGarbageDetailed();
        assertEquals(8192, retried.reclaimedBytes());
        assertEquals(1, retried.deletedFiles());
        assertTrue(retried.scanComplete());
        assertEquals(0, store.collectGarbageDetailed().reclaimedBytes());
        assertTrue(Files.exists(root.resolve(fresh)));
    }

    @Test void prepareBackupDoesNotRunOrphanGc() throws Exception {
        class CountingGc extends BlobMaintenance {
            int scans;
            CountingGc() { super(root); }
            @Override public GcResult deleteOrphansDetailed(Set<String> live) {
                scans++;
                return new GcResult(0, 0, 0, false);
            }
        }
        var gc = new CountingGc();
        var store = new SnapshotStore(root, new IndexStore(root.resolve("index.db")), gc);
        store.saveSnapshot(snapshot(OLD, 1, blob("live", 10)));
        int scansBefore = gc.scans;
        store.prepareBackup();
        assertEquals(scansBefore, gc.scans);
        assertEquals(List.of(OLD), store.listSnapshots());
    }

    @Test void referencedMissingFileAndUnreadableIndexSizeFailRatherThanAssumeZero() throws Exception {
        var store = new SnapshotStore(root);
        store.saveSnapshot(snapshot(OLD, 1, blob("live", 10)));
        Files.delete(root.resolve("blobs/live"));
        store.prepareBackup();
        assertEquals(List.of(OLD), store.listSnapshots());
        assertThrows(IOException.class, () -> RepositoryUsage.read(root, store.loadAllSnapshots()));
        blob("live", 10);
        Files.createDirectory(root.resolve("index.db.tmp"));
        assertThrows(IOException.class, () -> RepositoryUsage.read(root, store.loadAllSnapshots()));
    }

    @Test void postCommitCleanupRefreshesDedupAndNewFilesRemainRestorable() throws Exception {
        var store = new SnapshotStore(root);
        var blobs = new BlobStore(root, 6);
        Path world = Files.createDirectory(root.resolve("world"));
        byte[] content = "same world content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(world.resolve("level.dat"), content);
        var processor = new RegionProcessor(store::getIndex, blobs, 6);
        var initial = processor.scanWorld(world, null);
        var old = new SnapshotManifest(OLD, 1, "world");
        initial.populateManifest(old);
        store.saveSnapshot(old);
        var coordinator = new BackupCoordinator(new BackupContext(world, "world", "test"), store,
                processor, new RetentionPolicy().setMaxAgeDays(1));
        var saved = coordinator.runBackup(new TaskRegistry().startTask(TaskType.BACKUP).setRunning());
        assertEquals(List.of(saved.getSnapshotId()), new SnapshotStore(root).listSnapshots());
        assertArrayEquals(content, blobs.readFile(saved.getExtraFiles().getFirst().getBlobPath()));
    }

    @Test void failedCommitPreservesHistoryAndLeavesNewBlobForSafeRetry() throws Exception {
        class FailingIndex extends IndexStore {
            boolean fail;
            FailingIndex() { super(root.resolve("index.db")); }
            @Override public void save(MetadataIndex index) throws IOException {
                if (fail && index.snapshotCount() > 0) throw new IOException("injected commit failure");
                super.save(index);
            }
        }
        var disk = new FailingIndex();
        var store = new SnapshotStore(root, disk);
        store.saveSnapshot(snapshot(OLD, 1, blob("old", 10)));
        Path world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "new content");
        disk.fail = true;
        var coordinator = new BackupCoordinator(new BackupContext(world, "world", "test"), store,
                new RegionProcessor(store::getIndex, new BlobStore(root, 6), 6),
                new RetentionPolicy().setMaxAgeDays(1));
        assertThrows(IOException.class, () -> coordinator.runBackup(
                new TaskRegistry().startTask(TaskType.BACKUP).setRunning()));
        assertEquals(List.of(OLD), new SnapshotStore(root).listSnapshots());
        assertTrue(Files.exists(root.resolve("blobs/old")));
        var gc = store.collectGarbageDetailed();
        assertEquals(1, gc.deletedFiles());
        assertTrue(gc.reclaimedBytes() > 0);
        assertTrue(Files.exists(root.resolve("blobs/old")));
    }

    @Test void blobDirectoryWriteFailureAbortsInsteadOfCommittingPartialSnapshot() throws Exception {
        var store = new SnapshotStore(root);
        Path world = Files.createDirectory(root.resolve("world"));
        Path file = world.resolve("level.dat");
        Files.writeString(file, "new content");
        String hash = com.foldbackup.core.format.McaHasher.hashFile(file);
        blob(hash.substring(0, 2), 1); // 文件挡住了本应创建的 blob 子目录。
        var coordinator = new BackupCoordinator(new BackupContext(world, "world", "test"), store,
                new RegionProcessor(store::getIndex, new BlobStore(root, 6), 6),
                new RetentionPolicy().setEnabled(false));
        var status = new TaskRegistry().startTask(TaskType.BACKUP).setRunning();
        assertThrows(IOException.class, () -> coordinator.runBackup(status));
        assertTrue(store.listSnapshots().isEmpty());
        assertFalse(Files.exists(root.resolve("index.db")));
        assertNull(status.artifactId());
    }

    @Test void serializedSizeMatchesCommittedBytes() throws Exception {
        var store = new SnapshotStore(root);
        var sn = snapshot(OLD, 1, blob("live", 10));
        sn.setTags(List.of("手动", "manual"));
        sn.setMinecraftVersion("test");
        store.saveSnapshot(sn);
        assertEquals(Files.size(root.resolve("index.db")), IndexStore.serializedSize(store.getIndex()));
    }
}
