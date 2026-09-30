package com.foldbackup.core.archive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrderedCompressionPipelineTest {
    @Test
    void availablePartialStreamWaitsForCompletionWithoutBufferPressure() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Thread writer = Thread.currentThread();
            var partialReady = new CountDownLatch(1);
            var firstWritten = new CountDownLatch(1);
            var releasePartial = new CountDownLatch(1);
            var order = new ArrayList<Integer>();
            OrderedCompressionPipeline.run(3, 3, 3, 8, 64, (index, out) -> {
                if (index == 1) {
                    out.write(42);
                    partialReady.countDown();
                    await(releasePartial);
                } else if (index == 2) {
                    await(firstWritten);
                    awaitWaiting(writer); // Writer must wait at the next boundary, despite partial data.
                }
                return index;
            }, (index, result) -> {
                if (order.isEmpty()) {
                    assertEquals(0, index);
                    await(partialReady);
                    result.drainTo(java.io.OutputStream.nullOutputStream());
                    order.add(index);
                    firstWritten.countDown();
                } else {
                    if (order.size() == 1) {
                        assertEquals(2, index);
                        releasePartial.countDown();
                    }
                    result.drainTo(java.io.OutputStream.nullOutputStream());
                    order.add(index);
                }
            });
            assertEquals(List.of(0, 2, 1), order);
        });
    }

    @Test
    void queuedPartialBlocksCanBeExtendedWithoutRacingTheConsumer() throws Exception {
        var seen = new java.util.HashSet<Integer>();
        var metrics = OrderedCompressionPipeline.run(100, 4, 8, 17, 51, (index, output) -> {
            for (int n = 0; n < index * 7; n++) output.write((index + n) & 255);
            return index;
        }, (index, result) -> {
            var bytes = new java.io.ByteArrayOutputStream();
            assertEquals(index, result.drainTo(bytes));
            byte[] expected = new byte[index * 7];
            for (int n = 0; n < expected.length; n++) expected[n] = (byte) (index + n);
            assertArrayEquals(expected, bytes.toByteArray());
            assertTrue(seen.add(index));
        });
        assertEquals(100, seen.size());
        assertTrue(metrics.peakBufferBytes() <= 51);
    }

    @Test
    void completedEntryWinsOverAvailableUnfinishedStreamAtBoundary() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var writerStarted = new CountDownLatch(1);
            var fifthClaimed = new CountDownLatch(1);
            var releaseStreams = new CountDownLatch(1);
            var streamReady = new CountDownLatch(1);
            var order = new ArrayList<Integer>();
            OrderedCompressionPipeline.run(5, 3, 5, 8, 64, (index, output) -> {
                if (index != 0) await(writerStarted);
                if (index == 1) {
                    output.write(new byte[8]); streamReady.countDown(); await(releaseStreams);
                }
                if (index == 3) await(releaseStreams);
                if (index == 4) { fifthClaimed.countDown(); await(releaseStreams); }
                return index;
            }, (index, result) -> {
                if (order.isEmpty()) {
                    assertEquals(0, index);
                    writerStarted.countDown(); await(streamReady); await(fifthClaimed);
                } else if (order.size() == 1) {
                    assertEquals(2, index); // Tasks 1, 3 and 4 block; claiming 4 proves task 2 completed.
                    releaseStreams.countDown();
                }
                result.drainTo(java.io.OutputStream.nullOutputStream());
                order.add(index);
            });
            assertEquals(5, order.size());
        });
    }

    @Test
    void nonzeroSelectedRegionCanDrainBeyondFullPool() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var releaseFirst = new CountDownLatch(1);
            var order = new ArrayList<Integer>();
            var metrics = OrderedCompressionPipeline.run(3, 2, 3, 8, 16, (index, output) -> {
                if (index == 0) await(releaseFirst);
                output.write(new byte[1024]);
                return index;
            }, (index, result) -> {
                assertEquals(index, result.drainTo(java.io.OutputStream.nullOutputStream()));
                order.add(index);
                releaseFirst.countDown();
            });
            assertEquals(1, order.getFirst());
            assertEquals(3, new java.util.HashSet<>(order).size());
            assertTrue(metrics.peakBufferBytes() <= 16);
        });
    }

    @Test
    void cancellationWakesWriterAndProducerWaitingForSpace() throws Exception {
        var sharedFull = new CountDownLatch(1);
        var exited = new CountDownLatch(2);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var producer = new java.util.concurrent.atomic.AtomicReference<Thread>();
        Thread caller = new Thread(() -> {
            try {
                OrderedCompressionPipeline.<Integer>run(10, 2, 4, 8, 16, (index, out) -> {
                    try {
                        if (index == 0) await(new CountDownLatch(1));
                        producer.set(Thread.currentThread());
                        out.write(new byte[16]);
                        sharedFull.countDown();
                        out.write(new byte[8]);
                        return index;
                    } finally { exited.countDown(); }
                }, (index, result) -> await(new CountDownLatch(1)));
            } catch (Throwable failure) {
                error.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            await(sharedFull);
            awaitWaiting(producer.get());
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(IOException.class, error.get());
            assertTrue(interrupted.get());
            assertEquals(0, exited.getCount());
        } finally {
            caller.interrupt();
            caller.join(5000);
        }
    }

    @Test
    void manyEmptyRegionsKeepCompletionMetadataBounded() throws Exception {
        var written = new AtomicInteger();
        var seen = new java.util.HashSet<Integer>();
        var metrics = OrderedCompressionPipeline.<Integer>run(10000, 4, 8, 8, 16,
                (index, out) -> index, (index, result) -> {
                    assertEquals(index, result.drainTo(java.io.OutputStream.nullOutputStream()));
                    assertTrue(seen.add(index));
                    written.incrementAndGet();
                });
        assertEquals(10000, written.get());
        assertTrue(metrics.peakInFlight() <= 8);
        assertEquals(0, metrics.peakBufferBytes());
    }

    @Test
    void fullSharedPoolStillLetsSlowHeadStreamBeyondTotalCapacity() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var sharedFull = new CountDownLatch(1);
            var output = new java.util.HashMap<Integer, byte[]>();
            var metrics = OrderedCompressionPipeline.<Integer>run(12, 2, 4, 8, 24,
                    (index, out) -> {
                        if (index == 0) await(sharedFull);
                        byte[] bytes = new byte[257];
                        java.util.Arrays.fill(bytes, (byte) index);
                        if (index == 1) {
                            out.write(bytes, 0, 16); // Occupy both shared blocks before head proceeds.
                            sharedFull.countDown();
                            out.write(bytes, 16, bytes.length - 16);
                        } else out.write(bytes);
                        return index;
                    }, (index, result) -> {
                        var bytes = new java.io.ByteArrayOutputStream();
                        assertEquals(index, result.drainTo(bytes));
                        assertNull(output.put(index, bytes.toByteArray()));
                    });
            assertEquals(12, output.size());
            for (int i = 0; i < output.size(); i++) {
                byte[] expected = new byte[257];
                java.util.Arrays.fill(expected, (byte) i);
                assertArrayEquals(expected, output.get(i));
            }
            assertTrue(metrics.peakBufferBytes() <= 24);
            assertTrue(metrics.peakInFlight() <= 4);
        });
    }

    @Test
    void shortWritesAndWriterHeldBlockStayWithinBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var partialHeld = new CountDownLatch(1);
            var writerHasBlock = new CountDownLatch(1);
            var metrics = OrderedCompressionPipeline.<Integer>run(2, 2, 2, 8, 16,
                    (index, out) -> {
                        if (index == 0) {
                            await(partialHeld);
                            out.write(new byte[32]);
                        } else {
                            out.write(1); // The other producer's buffer wait makes this partial selectable.
                            partialHeld.countDown();
                            await(writerHasBlock);
                        }
                        return index;
                    }, (index, result) -> result.drainTo(new java.io.OutputStream() {
                        @Override public void write(int value) { }
                        @Override public void write(byte[] b, int off, int len) {
                            writerHasBlock.countDown();
                        }
                    }));
            assertTrue(metrics.peakBufferBytes() > 0 && metrics.peakBufferBytes() <= 16);
        });
    }

    @Test
    void streamingWriterFailureWakesBlockedProducersAndJoinsThem() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var sharedFull = new CountDownLatch(1);
            var exited = new CountDownLatch(2);
            IOException failure = new IOException("disk failed");
            var producer = new java.util.concurrent.atomic.AtomicReference<Thread>();
            IOException actual = assertThrows(IOException.class, () ->
                    OrderedCompressionPipeline.<Integer>run(10, 2, 4, 8, 16, (index, out) -> {
                        try {
                            if (index == 0) await(sharedFull);
                            if (index == 1) {
                                producer.set(Thread.currentThread());
                                out.write(new byte[16]);
                                sharedFull.countDown();
                            }
                            out.write(new byte[1024]);
                            return index;
                        } finally { exited.countDown(); }
                    }, (index, result) -> {
                        await(sharedFull);
                        awaitWaiting(producer.get());
                        result.drainTo(new java.io.OutputStream() {
                        @Override public void write(int value) throws IOException { throw failure; }
                        @Override public void write(byte[] b, int off, int len) throws IOException { throw failure; }
                        });
                    }));
            assertSame(failure, actual);
            assertEquals(0, exited.getCount());
        });
    }

    @Test
    void failureNotificationDoesNotNeedSpaceInFullDataQueue() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var headWaiting = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            IOException failure = new IOException("corrupt source");
            IOException actual = assertThrows(IOException.class, () ->
                    OrderedCompressionPipeline.<Integer>run(8, 2, 4, 8, 16, (index, out) -> {
                        if (index == 0) {
                            headWaiting.countDown();
                            try { await(new CountDownLatch(1)); }
                            finally { exited.countDown(); }
                        }
                        await(headWaiting);
                        out.write(new byte[8]);
                        throw failure;
                    }, (index, result) -> result.drainTo(java.io.OutputStream.nullOutputStream())));
            assertSame(failure, actual.getCause());
            assertEquals(0, exited.getCount());
        });
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Workers did not advance through the lookahead window");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static void awaitWaiting(Thread thread) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) throw new IOException("Producer never blocked on its buffer budget");
            Thread.yield();
        }
    }

    @Test
    void slowHeadDoesNotStopOtherWorkerFromTakingMoreJobs() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var fourthFinished = new CountDownLatch(1);
            var output = new ArrayList<Integer>();
            OrderedCompressionPipeline.run(8, 2, 4, 1, 2, (index, outputBytes) -> {
                if (index == 0) {
                    await(fourthFinished);
                }
                if (index == 6) {
                    fourthFinished.countDown();
                }
                return index;
            }, (index, result) -> output.add(result.drainTo(java.io.OutputStream.nullOutputStream())));
            assertNotEquals(0, output.getFirst());
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), output.stream().sorted().toList());
        });
    }

    @Test
    void slowWriterAllowsLookaheadButDoesNotAllowUnboundedResults() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var initialBatchFinished = new CountDownLatch(4);
            var started = new AtomicInteger();
            var output = new ArrayList<Integer>();
            OrderedCompressionPipeline.run(20, 2, 4, 1, 2, (index, outputBytes) -> {
                started.incrementAndGet();
                initialBatchFinished.countDown();
                return index;
            }, (index, result) -> {
                if (output.isEmpty()) {
                    await(initialBatchFinished);
                    assertEquals(4, started.get(), "including the result being written");
                }
                output.add(result.drainTo(java.io.OutputStream.nullOutputStream()));
            });
            assertEquals(20, output.size());
            assertEquals(20, new java.util.HashSet<>(output).size());
        });
    }

    @Test
    void writerFailureInterruptsAndJoinsWorkersBeforeReturning() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var workerStarted = new CountDownLatch(1);
            var workerExited = new CountDownLatch(1);
            IOException failure = new IOException("archive write failed");
            IOException actual = assertThrows(IOException.class, () ->
                    OrderedCompressionPipeline.<Integer>run(8, 2, 4, 1, 2, (index, outputBytes) -> {
                        if (index != 0) {
                            workerStarted.countDown();
                            try {
                                await(new CountDownLatch(1));
                            } finally {
                                workerExited.countDown();
                            }
                        }
                        return index;
                    }, (index, result) -> {
                        await(workerStarted);
                        throw failure;
                    }));
            assertSame(failure, actual);
            assertEquals(0, workerExited.getCount());
        });
    }

    @Test
    void compressionFailureRetainsOriginalCause() {
        IOException failure = new IOException("corrupt region");
        IOException actual = assertThrows(IOException.class, () ->
                OrderedCompressionPipeline.<Integer>run(8, 2, 4, 1, 2, (index, outputBytes) -> {
                    throw failure;
                }, (index, result) -> fail("Failed result must not be written")));
        assertSame(failure, actual.getCause());
    }
}
