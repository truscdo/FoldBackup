package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.LrhTestSupport;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.format.ZstdCompressionMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTasksTest {
    @Test void collectorRunsOnCallerAndCanStopWorkersWhenSpoolingFails() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Thread caller = Thread.currentThread();
            var running = new AtomicInteger();
            var started = new CountDownLatch(1);
            IOException diskFailure = new IOException("plan spool full");
            IOException failure = assertThrows(IOException.class, () -> ArchiveTasks.collect(100, 2, 2, i -> {
                if (i == 0) { await(started); return i; }
                running.incrementAndGet();
                started.countDown();
                try { await(new CountDownLatch(1)); return i; }
                finally { running.decrementAndGet(); }
            }, (i, value) -> {
                assertSame(caller, Thread.currentThread());
                throw diskFailure;
            }, null));
            assertSame(diskFailure, failure);
            assertEquals(0, running.get());
        });
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    @Test void slowFirstTaskDoesNotBlockFurtherWindowsAndResultsStayOrdered() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var later = new CountDownLatch(1);
            var results = ArchiveTasks.run(20, 2, i -> {
                if (i == 0) await(later);
                if (i == 10) later.countDown();
                return i;
            });
            for (int i = 0; i < 20; i++) assertEquals(i, results.get(i));
        });
    }
    @Test void firstFailureInterruptsOtherJobsAndJoinsBeforeReturning() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var started = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            IOException cause = new IOException("source failed");
            var failure = assertThrows(IOException.class, () -> ArchiveTasks.run(100, 2, i -> {
                if (i == 0) {
                    started.countDown();
                    try { await(new CountDownLatch(1)); } finally { exited.countDown(); }
                }
                await(started);
                throw cause;
            }));
            assertSame(cause, failure.getCause());
            assertEquals(0, exited.getCount());
        });
    }
    @Test void callerCancellationJoinsRunningJobs() throws Exception {
        var started = new CountDownLatch(2);
        var exited = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                ArchiveTasks.run(50, 2, i -> {
                    started.countDown();
                    try { await(new CountDownLatch(1)); } finally { exited.countDown(); }
                    return i;
                });
            } catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            await(started); caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interrupted.get());
            assertEquals(0, exited.getCount());
        } finally { caller.interrupt(); caller.join(5000); }
    }

    // ── source frame level ───────────────────────────────────

    /** 一次配额服务整批，故取批次内源帧级别的最大值。 */
    @Test void maxSourceFrameLevelTakesTheBatchMaximum(@TempDir Path dir) throws IOException {
        writeLrh(dir.resolve("a.lrh"), 3);
        writeLrh(dir.resolve("b.lrh"), 19);
        assertEquals(19, ArchiveTasks.maxSourceFrameLevel(
                List.of(dir.resolve("a.lrh"), dir.resolve("b.lrh"))));
        assertEquals(19, ArchiveTasks.maxSourceFrameLevel(
                List.of(dir.resolve("b.lrh"), dir.resolve("a.lrh"))));
        assertEquals(0, ArchiveTasks.maxSourceFrameLevel(List.of()));
    }

    /**
     * 头探测是定尺而非校验：读不出的源按 zstd 上界计，真实错误留给随后的实际读取。
     */
    @Test void maxSourceFrameLevelFallsBackToTheUpperBoundWhenAProbeFails(@TempDir Path dir) {
        assertEquals(ZstdCompressionMemory.MAX_LEVEL,
                ArchiveTasks.maxSourceFrameLevel(List.of(dir.resolve("missing.lrh"))));
    }

    private static void writeLrh(Path destination, int level) throws IOException {
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] { 1, 2, 3 }, 0, 0));
        region.setTimestamp(0, 7);
        LrhTestSupport.writeRegionLinear(destination, region, level);
    }
}
