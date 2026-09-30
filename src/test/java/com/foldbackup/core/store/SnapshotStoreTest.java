package com.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import com.foldbackup.core.backup.RetentionPolicy;
import com.foldbackup.core.backup.BackupContext;
import com.foldbackup.core.backup.BackupCoordinator;
import com.foldbackup.core.backup.RegionProcessor;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskType;

import static org.junit.jupiter.api.Assertions.*;

/** SnapshotStore 持久化与重启恢复测试。 */
class SnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void backupCoordinatorCommitsSnapshotAndRetentionOnce() throws Exception {
        CountingIndexStore disk = new CountingIndexStore(tempDir.resolve("index.db"));
        SnapshotStore store = new SnapshotStore(tempDir, disk);
        store.saveSnapshot(withFile("S-20260902_120000", 1000, "old"));
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "current world");
        BlobStore blobs = new BlobStore(tempDir, 6);
        BackupCoordinator coordinator = new BackupCoordinator(
                new BackupContext(world, "world", "test"), store,
                new RegionProcessor(store::getIndex, blobs, 6),
                new RetentionPolicy().setMaxAgeDays(1));
        var status = new TaskRegistry().startTask(TaskType.BACKUP).setRunning();
        SnapshotManifest saved = coordinator.runBackup(status);
        assertEquals(2, disk.saves);
        assertEquals(List.of(saved.getSnapshotId()), store.listSnapshots());
        assertEquals(store.listSnapshots(), new SnapshotStore(tempDir).listSnapshots());
        assertFalse(Files.exists(tempDir.resolve("blobs/old")));
        for (String blob : store.getIndex().collectAllBlobPaths()) {
            assertTrue(Files.exists(tempDir.resolve(blob)));
        }
    }

    @Test
    void failedSaveAndRetentionKeepMemoryDiskAndBlobsThenRetry() throws Exception {
        CountingIndexStore disk = new CountingIndexStore(tempDir.resolve("index.db"));
        SnapshotStore store = new SnapshotStore(tempDir, disk);
        SnapshotManifest first = withFile("S-20260902_120000", 1000, "old");
        store.saveSnapshot(first);
        MetadataIndex before = store.getIndex();
        byte[] bytes = Files.readAllBytes(tempDir.resolve("index.db"));
        SnapshotManifest second = withFile("S-20260902_120001", System.currentTimeMillis() / 1000L, "new");
        Files.createDirectory(tempDir.resolve("index.db.tmp"));
        RetentionPolicy policy = new RetentionPolicy().setMaxAgeDays(1);

        assertThrows(IOException.class, () -> store.saveSnapshot(second, policy));
        assertEquals(before.snapshotCount(), store.getIndex().snapshotCount());
        assertEquals(before.collectAllBlobPaths(), store.getIndex().collectAllBlobPaths());
        assertEquals(1, before.metadataCount());
        assertNull(store.loadSnapshot(second.getEpochSeconds()));
        assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve("index.db")));
        assertTrue(Files.exists(tempDir.resolve("blobs/old")));
        assertTrue(Files.exists(tempDir.resolve("blobs/new")));

        Files.delete(tempDir.resolve("index.db.tmp"));
        int attempts = disk.saves;
        store.saveSnapshot(second, policy);
        assertEquals(attempts + 1, disk.saves);
        assertEquals(List.of(second.getSnapshotId()), store.listSnapshots());
        assertEquals(store.listSnapshots(), new SnapshotStore(tempDir).listSnapshots());
        assertEquals(1, store.getIndex().metadataCount());
        assertFalse(Files.exists(tempDir.resolve("blobs/old")));
        assertTrue(Files.exists(tempDir.resolve("blobs/new")));
    }

    @Test
    void failedDeleteKeepsStateAndSuccessfulDeleteCommitsOnce() throws Exception {
        CountingIndexStore disk = new CountingIndexStore(tempDir.resolve("index.db"));
        SnapshotStore store = new SnapshotStore(tempDir, disk);
        SnapshotManifest first = withFile("S-20260902_120000", 1000, "shared");
        store.saveSnapshot(first);
        SnapshotManifest second = withFile("S-20260902_120001", 1001, "shared");
        store.saveSnapshot(second);
        MetadataIndex before = store.getIndex();
        byte[] bytes = Files.readAllBytes(tempDir.resolve("index.db"));
        Files.createDirectory(tempDir.resolve("index.db.tmp"));
        assertThrows(IOException.class, () -> store.deleteSnapshot(first.getEpochSeconds()));
        assertEquals(before.snapshotCount(), store.getIndex().snapshotCount());
        assertEquals(before.collectAllBlobPaths(), store.getIndex().collectAllBlobPaths());
        assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve("index.db")));
        assertTrue(Files.exists(tempDir.resolve("blobs/shared")));
        Files.delete(tempDir.resolve("index.db.tmp"));
        int attempts = disk.saves;
        store.deleteSnapshots(List.of(first.getEpochSeconds(), first.getEpochSeconds()));
        assertEquals(attempts + 1, disk.saves);
        assertEquals(1, store.getIndex().metadataCount());
        assertTrue(Files.exists(tempDir.resolve("blobs/shared")));
        store.deleteSnapshot(second.getEpochSeconds());
        assertEquals(attempts + 2, disk.saves);
        assertEquals(0, store.getIndex().metadataCount());
        assertFalse(Files.exists(tempDir.resolve("blobs/shared")));
        assertTrue(new SnapshotStore(tempDir).listSnapshots().isEmpty());
    }

    @Test
    void noOpDeletionAndGcRetryNeverWriteIndex() throws Exception {
        CountingIndexStore disk = new CountingIndexStore(tempDir.resolve("index.db"));
        SnapshotStore store = new SnapshotStore(tempDir, disk);
        store.deleteSnapshots(List.of());
        store.deleteSnapshot(1000L);
        assertEquals(0, disk.saves);
        store.saveSnapshot(withFile("S-20260902_120000", 1000, "live"));
        Files.writeString(tempDir.resolve("blobs/orphan"), "left over from incomplete GC");
        assertEquals(1, store.collectGarbage());
        assertEquals(0, store.collectGarbage());
        store.deleteSnapshots(List.of());
        assertEquals(1, disk.saves);
        assertTrue(Files.exists(tempDir.resolve("blobs/live")));
    }

    private SnapshotManifest withFile(String id, long time, String blob) throws IOException {
        Files.createDirectories(tempDir.resolve("blobs"));
        Files.writeString(tempDir.resolve("blobs/" + blob), blob);
        SnapshotManifest manifest = snapshot(id, time);
        manifest.addExtraFile(new FileEntry("level.dat", "a".repeat(32), "blobs/" + blob, 3, 3));
        return manifest;
    }

    private static class CountingIndexStore extends IndexStore {
        int saves;

        CountingIndexStore(Path path) {
            super(path);
        }

        @Override
        public void save(MetadataIndex index) throws IOException {
            saves++;
            super.save(index);
        }
    }

    /** 验证重建 SnapshotStore 后仍保持最新优先顺序，并可直接取得最新清单。 */
    @Test
    void reloadPreservesNewestFirstOrderAndLatestSnapshot() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);

        SnapshotManifest first = snapshot("S-20260902_120000", 1_000L);
        SnapshotManifest second = snapshot("S-20260902_120001", 1_001L);
        SnapshotManifest third = snapshot("S-20260902_120002", 1_002L);

        store.saveSnapshot(first);
        store.saveSnapshot(second);
        store.saveSnapshot(third);

        SnapshotStore reloaded = new SnapshotStore(tempDir);

        assertEquals(List.of(third.getSnapshotId(), second.getSnapshotId(), first.getSnapshotId()),
                reloaded.listSnapshots());
        assertEquals(third.getSnapshotId(), reloaded.loadAllSnapshots().get(0).getSnapshotId());
        assertEquals(third.getSnapshotId(), reloaded.loadLatestSnapshot().getSnapshotId());
    }

    @Test
    void epochIsUniqueAndUserIdResolvesWithoutParsingItsText() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        SnapshotManifest first = snapshot("S-20260902_120000", 1_788_351_200L);
        store.saveSnapshot(first);

        assertEquals(1, Files.readAllBytes(tempDir.resolve("index.db"))[4] & 0xff,
                "Epoch-key changes retain index.db version 1");
        assertEquals(first.getEpochSeconds(), store.resolveEpoch(first.getSnapshotId()));
        assertEquals(first.getEpochSeconds(), new SnapshotStore(tempDir).resolveEpoch(first.getSnapshotId()));
        String localEpochZeroId = "S-" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(Instant.EPOCH.atZone(ZoneId.systemDefault()));
        assertEquals(localEpochZeroId, SnapshotStore.generateSnapshotId(0));
        assertThrows(IOException.class, () -> store.resolveEpoch(localEpochZeroId));
        assertThrows(IOException.class, () -> store.saveSnapshot(snapshot("S-20260902_120001", 1_788_351_200L)));
    }

    /** 创建用于测试的最小快照清单。 */
    private static SnapshotManifest snapshot(String id, long time) {
        return new SnapshotManifest(id, time, "world");
    }
}
