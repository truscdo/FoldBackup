package io.github.truscdo.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreConcurrencyTest {
    @TempDir Path root;

    private static SnapshotManifest snapshot(int n) {
        return new SnapshotManifest("S-20260907_1200%02d".formatted(n), n, "world");
    }

    @Test
    void queriesAndInputManifestsCannotModifyCommittedState() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        SnapshotManifest input = snapshot(0);
        input.getTags().add("original");
        input.addRegion(new RegionEntry("r", "a", "blobs/r", 1, 2));
        input.addExtraFile(new FileEntry("f", "c", "blobs/f", 3, 4));
        store.saveSnapshot(input);
        MetadataIndex view = store.getIndex();
        List<SnapshotManifest> list = view.getSnapshots();
        input.setWorldName("changed");
        input.getRegions().getFirst().setBlobPath("changed");
        SnapshotManifest query = store.loadSnapshot(snapshot(0).getEpochSeconds());
        query.getTags().clear();
        query.getRegions().getFirst().setBlobPath("changed");
        query.getExtraFiles().getFirst().setBlobPath("changed");
        query.getSummary().setNewBlobBytes(123);
        list.getFirst().setSnapshotId("changed");
        assertThrows(UnsupportedOperationException.class, () -> view.removeSnapshot(input.getEpochSeconds()));
        assertThrows(UnsupportedOperationException.class, () -> view.getEntryHashes(input.getEpochSeconds()).clear());
        SnapshotManifest intact = store.loadSnapshot(input.getEpochSeconds());
        assertEquals("world", intact.getWorldName());
        assertEquals(List.of("original"), intact.getTags());
        assertEquals("blobs/r", intact.getRegions().getFirst().getBlobPath());
        assertEquals("blobs/f", intact.getExtraFiles().getFirst().getBlobPath());
        assertEquals(0, intact.getSummary().getNewBlobBytes());
        store.deleteSnapshot(input.getEpochSeconds());
        assertEquals(1, view.snapshotCount());
        assertEquals(1, list.size());
        assertEquals(0, store.getIndex().snapshotCount());
    }

    @Test
    void staleInstancesReloadBeforeSavingDeletingAndGc() throws Exception {
        SnapshotStore first = new SnapshotStore(root);
        SnapshotStore stale = new SnapshotStore(root);
        first.saveSnapshot(snapshot(0));
        stale.saveSnapshot(snapshot(1));
        assertEquals(2, new SnapshotStore(root).listSnapshots().size());
        Files.createDirectories(root.resolve("blobs"));
        Files.writeString(root.resolve("blobs/live"), "live");
        SnapshotManifest live = snapshot(2);
        live.addExtraFile(new FileEntry("f", "a", "blobs/live", 4, 4));
        stale.saveSnapshot(live);
        first.collectGarbage();
        assertTrue(Files.exists(root.resolve("blobs/live")));
        first.deleteSnapshot(snapshot(0).getEpochSeconds());
        assertEquals(List.of(live.getSnapshotId(), snapshot(1).getSnapshotId()),
                new SnapshotStore(root).listSnapshots());
    }

    @Test
    void concurrentWritersAndReadersSeeCompleteViews() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int writer = 0; writer < 4; writer++) {
                int offset = writer * 10;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        SnapshotManifest manifest = snapshot(offset + i);
                        manifest.addExtraFile(new FileEntry("file", "hash-" + (offset + i),
                                "blobs/" + (offset + i), 1, 1));
                        store.saveSnapshot(manifest);
                        if (i % 2 == 0) store.deleteSnapshot(snapshot(offset + i).getEpochSeconds());
                    }
                    return null;
                }));
            }
            tasks.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    MetadataIndex view = store.getIndex();
                    List<SnapshotManifest> snapshots = view.getSnapshots();
                    assertEquals(snapshots.size(), view.snapshotCount());
                    for (SnapshotManifest sn : snapshots) {
                        assertNotNull(view.loadSnapshot(sn.getEpochSeconds()));
                        List<String> hashes = view.getEntryHashes(sn.getEpochSeconds());
                        assertEquals(1, hashes.size());
                        assertEquals(sn.getExtraFiles().getFirst().getBlobPath(),
                                view.getMetadata(hashes.getFirst()).getBlobPath());
                    }
                }
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(20, store.listSnapshots().size());
        assertEquals(store.listSnapshots(), new SnapshotStore(root).listSnapshots());
    }

    @Test
    void sessionProtectsUncommittedBlobsFromOtherStoreGcAndReleasesOnFailure() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        SnapshotStore other = new SnapshotStore(root);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> gc;
            try (SnapshotStore.WriteSession ignored = store.beginWrite()) {
                Files.createDirectories(root.resolve("blobs"));
                Files.writeString(root.resolve("blobs/pending"), "pending");
                CountDownLatch started = new CountDownLatch(1);
                gc = pool.submit(() -> { started.countDown(); return other.collectGarbage(); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> gc.get(200, TimeUnit.MILLISECONDS));
                SnapshotManifest manifest = snapshot(0);
                manifest.addExtraFile(new FileEntry("f", "a", "blobs/pending", 7, 7));
                store.saveSnapshot(manifest);
            }
            assertEquals(0, gc.get(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(root.resolve("blobs/pending")));
            assertThrows(java.io.IOException.class, () -> store.saveSnapshot(snapshot(0)));
            pool.submit(() -> { other.saveSnapshot(snapshot(1)); return null; }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void separateProcessReloadsStaleIndexAndBlocksOtherWriters() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        Process child = startWriter();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream()));
            assertEquals("READY", pool.submit(output::readLine).get(10, TimeUnit.SECONDS));
            store.saveSnapshot(snapshot(0));
            child.getOutputStream().write(1);
            child.getOutputStream().flush();
            assertEquals("LOCKED", pool.submit(output::readLine).get(10, TimeUnit.SECONDS));
            Future<?> write = pool.submit(() -> { store.saveSnapshot(snapshot(2)); return null; });
            try {
                assertThrows(TimeoutException.class, () -> write.get(200, TimeUnit.MILLISECONDS));
            } finally {
                child.getOutputStream().write(1);
                child.getOutputStream().flush();
            }
            write.get(10, TimeUnit.SECONDS);
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertEquals(3, new SnapshotStore(root).listSnapshots().size());
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void terminatedProcessReleasesDirectoryLock() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        Process child = startWriter();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            try {
                BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream()));
                assertEquals("READY", pool.submit(output::readLine).get(10, TimeUnit.SECONDS));
                child.getOutputStream().write(1);
                child.getOutputStream().flush();
                assertEquals("LOCKED", pool.submit(output::readLine).get(10, TimeUnit.SECONDS));
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                pool.submit(() -> { store.saveSnapshot(snapshot(0)); return null; }).get(10, TimeUnit.SECONDS);
                assertEquals(List.of(snapshot(0).getSnapshotId()), new SnapshotStore(root).listSnapshots());
            } finally {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void startupCleanupWaitsForBlobWriter() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> cleanup;
            try (SnapshotStore.WriteSession ignored = store.beginWrite()) {
                Path pending = root.resolve("pending.wip");
                Files.writeString(pending, "pending");
                CountDownLatch started = new CountDownLatch(1);
                cleanup = pool.submit(() -> {
                    started.countDown();
                    return new BlobMaintenance(root).cleanupWipFiles();
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> cleanup.get(200, TimeUnit.MILLISECONDS));
                assertTrue(Files.exists(pending));
                Files.delete(pending);
            }
            assertEquals(0, cleanup.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentStoresCannotNestAndOverwriteOuterTransaction() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        SnapshotStore other = new SnapshotStore(root);
        try (SnapshotStore.WriteSession ignored = store.beginWrite()) {
            assertThrows(java.io.IOException.class, () -> other.saveSnapshot(snapshot(1)));
            store.saveSnapshot(snapshot(0));
        }
        other.saveSnapshot(snapshot(1));
        assertEquals(2, new SnapshotStore(root).listSnapshots().size());
    }

    private Process startWriter() throws Exception {
        String classpath = System.getProperty("foldbackup.testClasspath");
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, SnapshotWriterProcess.class.getName(), root.toString(), snapshot(1).getSnapshotId())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
}
