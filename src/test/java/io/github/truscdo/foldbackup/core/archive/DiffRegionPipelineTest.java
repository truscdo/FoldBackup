package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiffRegionPipelineTest {
    @TempDir Path temp;

    private RegionStream.Source source(AtomicInteger reads, int... contents) {
        return consumer -> {
            reads.incrementAndGet();
            byte[] borrowed = new byte[1];
            for (int i = 0; i < contents.length; i++) {
                consumer.begin(i, 100 + i);
                if (contents[i] >= 0) {
                    borrowed[0] = (byte) contents[i]; consumer.data(borrowed, 0, 1);
                }
                consumer.end();
            }
        };
    }

    private LrhdTestSupport.LrhdFile run(List<DiffRegionPipeline.RegionTask> tasks, long chunkLimit)
            throws Exception {
        Path path = Files.createTempFile(temp, "diff", ".encoded");
        try (var output = Files.newOutputStream(path);
                var writer = new LrhdEncodingWriter(output, Path.of(path + ".index"), 1, 2)) {
            DiffRegionPipeline.run(tasks, chunkLimit, temp, writer);
            writer.finish();
        }
        try (var input = LrhdLayoutSource.open(path, 0, Files.size(path))) { return LrhdTestSupport.read(input.readAllBytes()); }
    }

    @Test void decodesEachSourceOnceAndPreservesRegionAndChunkOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var reads = new AtomicInteger();
            var result = run(List.of(
                    new DiffRegionPipeline.RegionTask(-1, 0, source(reads, 1, 2, 3), source(reads, 1, 7, -1)),
                    new DiffRegionPipeline.RegionTask(2, 0, null, source(reads, 9, 10))), 1);
            assertEquals(3, reads.get());
            assertEquals(List.of(-1, 2), result.regions.stream().map(r -> r.rx).toList());
            assertEquals(4, result.allChunks().size());
            assertEquals(1, result.allChunks().getFirst().slotIndex());
            assertArrayEquals(new byte[] {7}, result.chunkNbt(result.allChunks().getFirst()));
            assertTrue(result.allChunks().get(1).deleted);
            assertArrayEquals(new byte[] {9}, result.chunkNbt(result.allChunks().get(2)));
            assertArrayEquals(new byte[] {10}, result.chunkNbt(result.allChunks().get(3)));
        });
    }

    @Test void manyRegionsMakeProgressWithOneChunkBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var reads = new AtomicInteger(); var tasks = new ArrayList<DiffRegionPipeline.RegionTask>();
            for (int i = 0; i < 20; i++) tasks.add(new DiffRegionPipeline.RegionTask(i, 0, null, source(reads, i, i + 1)));
            assertEquals(40, run(tasks, 1).allChunks().size());
            assertEquals(20, reads.get());
        });
    }

    @Test void unchangedAndDeletionOnlyRegionsNeedNoCompressedFrame() throws Exception {
        var reads = new AtomicInteger();
        var result = run(List.of(
                new DiffRegionPipeline.RegionTask(0, 0, source(reads, 7), source(reads, 7)),
                new DiffRegionPipeline.RegionTask(1, 0, source(reads, 8), source(reads, -1))), 1);
        assertEquals(1, result.regions.size());
        assertTrue(result.allChunks().getFirst().deleted);
        assertEquals(4, reads.get());
    }

    @Test void oversizedChunkSpillsAndLateSourceFailurePropagates() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            RegionStream.Source oversized = consumer -> {
                consumer.begin(0, 0); consumer.data(new byte[2], 0, 2); consumer.end();
            };
            var spilled = run(List.of(new DiffRegionPipeline.RegionTask(0, 0, null, oversized)), 1);
            assertArrayEquals(new byte[2], spilled.chunkNbt(spilled.allChunks().getFirst()));
            assertNoSpills();
            var reads = new AtomicInteger();
            RegionStream.Source broken = consumer -> {
                source(reads, 5).read(consumer); throw new IOException("late LRH footer failure");
            };
            IOException failure = assertThrows(IOException.class, () -> run(List.of(
                    new DiffRegionPipeline.RegionTask(0, 0, null, broken),
                    new DiffRegionPipeline.RegionTask(1, 0, null, source(reads, 6))), 1));
            assertEquals("late LRH footer failure", failure.getMessage());
        });
    }

    @Test void frameAllowanceGivesTheWholeBudgetToZstd() {
        int cpus = Math.min(8, Runtime.getRuntime().availableProcessors());
        // 每 worker 内存取自生产口径，避免测试手抄一个与实现同源的数值。
        long perWorker = DiffResourceBudget.mtTaskBytes(19);
        var tiny = DiffResourceBudget.pipeline(1, 19, 8);
        assertEquals(1, tiny.zstdWorkers());
        assertEquals(1, tiny.chunkBytes());
        // Directories are encoded one at a time, so a frame may take every core and the whole
        // budget: the largest frame is what saturates the machine.
        var large = DiffResourceBudget.pipeline(8, 19, 8L << 30);
        assertEquals(cpus, large.zstdWorkers());
        assertTrue(large.chunkBytes() >= 1 && large.chunkBytes() <= 64L << 20);
        assertTrue((long) large.zstdWorkers() * perWorker + 2 * large.chunkBytes() <= (8L << 30),
                "allowances stay within budget");
        // A small heap still permits at least one worker instead of collapsing to zero.
        var smallHeap = DiffResourceBudget.pipeline(24, 19, 768L << 20);
        assertTrue(smallHeap.zstdWorkers() >= 1);
        assertTrue((long) smallHeap.zstdWorkers() * perWorker + 2 * smallHeap.chunkBytes() <= (768L << 20));
    }
    @Test void cancellationInterruptsBlockedSource() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var caught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        RegionStream.Source blocked = consumer -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            finally { exited.countDown(); }
        };
        Thread caller = new Thread(() -> {
            try { run(List.of(new DiffRegionPipeline.RegionTask(0, 0, null, blocked)), 1); }
            catch (Throwable e) { caught.set(e); }
        });
        caller.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive());
            assertEquals(0, exited.getCount());
            assertInstanceOf(IOException.class, caught.get());
        } finally { caller.interrupt(); caller.join(5000); }
    }
    @Test void fragmentedBorrowedInputCrossesBufferBoundariesWithoutCopiesOfWholeChunk() throws Exception {
        byte[] payload = new byte[150001]; new Random(23).nextBytes(payload);
        RegionStream.Source source = consumer -> {
            byte[] borrowed = new byte[73]; consumer.begin(0, 123);
            for (int offset = 0; offset < payload.length; offset += borrowed.length) {
                int n = Math.min(borrowed.length, payload.length - offset);
                System.arraycopy(payload, offset, borrowed, 0, n);
                consumer.data(borrowed, 0, n);
                Arrays.fill(borrowed, (byte) 0);
            }
            consumer.end();
        };
        var result = run(List.of(new DiffRegionPipeline.RegionTask(0, 0, null, source)), payload.length);
        assertArrayEquals(payload, result.chunkNbt(result.allChunks().getFirst()));
    }
    private void assertNoSpills() throws IOException {
        try (var files = Files.list(temp)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("lrhd-chunk-")));
        }
    }

    @Test void failureCleansOpenSpill() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            RegionStream.Source failing = consumer -> {
                consumer.begin(0, 0); consumer.data(new byte[4], 0, 4);
                throw new IOException("source failed with an open spill");
            };
            assertThrows(IOException.class, () -> run(List.of(
                    new DiffRegionPipeline.RegionTask(0, 0, null, failing)), 1));
            assertNoSpills();
        });
    }
}
