package com.foldbackup.core.archive;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
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
}
