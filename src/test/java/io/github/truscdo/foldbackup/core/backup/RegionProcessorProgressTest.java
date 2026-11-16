package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegionProcessorProgressTest {

    @TempDir
    Path temp;

    @Test
    void slowHeadTaskDoesNotBlockCompletionProgressOfSubsequentTasks() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("a.dat"), "first");
        Files.writeString(world.resolve("b.dat"), "second");

        var headEntered = new CountDownLatch(1);
        var releaseHead = new CountDownLatch(1);
        var fastCompleted = new CountDownLatch(1);
        var slowFile = new AtomicReference<String>();

        var blobs = new BlobStore(temp.resolve("store"), 1);
        var processor = new RegionProcessor(blobs, 1, new MetadataIndex(), BackupFilter.defaults(), 2, 256) {
            @Override
            io.github.truscdo.foldbackup.core.format.McaHasher.FileHash inspectExtraFile(Path file) throws IOException {
                String name = file.getFileName().toString();
                if (slowFile.compareAndSet(null, name)) {
                    headEntered.countDown();
                    try {
                        assertTrue(releaseHead.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                return super.inspectExtraFile(file);
            }
        };

        try (var pool = Executors.newSingleThreadExecutor()) {
            var scanFuture = pool.submit(() -> processor.scanWorld(world, null, event -> {
                if (event instanceof ScanEvent.Completed c && !c.relPath().equals(slowFile.get())) {
                    fastCompleted.countDown();
                }
            }));
            try {
                assertTrue(headEntered.await(5, TimeUnit.SECONDS));
                // 即使首个任务仍阻塞在检验逻辑中，后续快任务的 Completed 进度也必须已立即上报
                assertTrue(fastCompleted.await(5, TimeUnit.SECONDS),
                        "fast task completion must not wait behind a slow head task in BoundedWork");
            } finally {
                releaseHead.countDown();
            }
            var result = scanFuture.get(10, TimeUnit.SECONDS);
            assertEquals(2, result.getFiles().size());
        }
    }
}
