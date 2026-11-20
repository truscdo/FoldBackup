package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RethrowTest {

    @Test
    void asIoReturnsIoExceptionUnwrappedAndWrapsCheckedCauses() {
        IOException io = new IOException("disk full");
        assertSame(io, Rethrow.asIo(io, "ignored context"));

        Exception checked = new Exception("checked");
        IOException wrapped = Rethrow.asIo(checked, "operation failed");
        assertEquals("operation failed", wrapped.getMessage());
        assertSame(checked, wrapped.getCause());

        IOException withoutCause = Rethrow.asIo(null, "missing cause");
        assertEquals("missing cause", withoutCause.getMessage());
        assertNull(withoutCause.getCause());
    }

    @Test
    void asIoRethrowsRuntimeExceptionAndErrorDirectly() {
        IllegalStateException runtime = new IllegalStateException("bug");
        assertSame(runtime, assertThrows(IllegalStateException.class,
                () -> Rethrow.asIo(runtime, "context")));

        AssertionError error = new AssertionError("fatal");
        assertSame(error, assertThrows(AssertionError.class,
                () -> Rethrow.asIo(error, "context")));
    }

    @Test
    void awaitIoReturnsCompletedValueAndUnwrapsFailures() throws Exception {
        assertEquals("ok", Rethrow.awaitIo(CompletableFuture.completedFuture("ok"), "interrupted", "failed"));

        IOException io = new IOException("read error");
        CompletableFuture<String> failedIo = CompletableFuture.failedFuture(io);
        assertSame(io, assertThrows(IOException.class,
                () -> Rethrow.awaitIo(failedIo, "interrupted", "failed")));

        IllegalArgumentException runtime = new IllegalArgumentException("bad arg");
        CompletableFuture<String> failedRuntime = CompletableFuture.failedFuture(runtime);
        assertSame(runtime, assertThrows(IllegalArgumentException.class,
                () -> Rethrow.awaitIo(failedRuntime, "interrupted", "failed")));

        OutOfMemoryError error = new OutOfMemoryError("oom");
        CompletableFuture<String> failedError = CompletableFuture.failedFuture(error);
        assertSame(error, assertThrows(OutOfMemoryError.class,
                () -> Rethrow.awaitIo(failedError, "interrupted", "failed")));
    }

    @Test
    void awaitIoRestoresInterruptFlagAndWrapsInterruptedException() throws Exception {
        Future<String> interruptedFuture = new Future<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return false; }
            @Override public String get() throws InterruptedException {
                throw new InterruptedException("stop");
            }
            @Override public String get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
                throw new InterruptedException("stop");
            }
        };
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try {
            IOException thrown = assertThrows(IOException.class,
                    () -> Rethrow.awaitIo(interruptedFuture, "task interrupted", "task failed"));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            assertEquals("task interrupted", thrown.getMessage());
            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(interruptPreserved.get());
        } finally {
            Thread.interrupted();
        }
    }
}
