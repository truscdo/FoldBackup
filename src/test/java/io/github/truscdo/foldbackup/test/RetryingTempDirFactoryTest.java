package io.github.truscdo.foldbackup.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryingTempDirFactoryTest {
    @Test void retriesTransientFailures() throws Exception {
        var attempts = new AtomicInteger();
        RetryingTempDirFactory.retryCleanup(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) throw new DirectoryNotEmptyException("temporary");
            if (attempt == 2) throw new AccessDeniedException("temporary");
        }, 3, 0);
        assertEquals(3, attempts.get());
    }

    @Test void permanentFailureRemainsVisibleAndRetriesAreBounded() {
        var attempts = new AtomicInteger();
        var failure = new DirectoryNotEmptyException("still busy");
        assertSame(failure, assertThrows(IOException.class, () ->
                RetryingTempDirFactory.retryCleanup(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }, 3, 0)));
        assertEquals(3, attempts.get());
        var other = new IOException("not a transient deletion error");
        assertSame(other, assertThrows(IOException.class, () ->
                RetryingTempDirFactory.retryCleanup(() -> { throw other; }, 3, 0)));
    }

    @Test void closeDeletesOwnedDirectoryAndCanBeRepeated() throws Exception {
        var factory = new RetryingTempDirFactory();
        var directory = factory.createTempDirectory(null, null);
        try (factory) {
            Files.writeString(Files.createDirectories(directory.resolve("nested")).resolve("data"), "test");
        }
        assertFalse(Files.exists(directory));
        factory.close();
    }

    @Test void interruptionStopsRetriesAndPreservesInterruptFlag() {
        var failure = new AccessDeniedException("busy");
        Thread.currentThread().interrupt();
        try {
            assertSame(failure, assertThrows(IOException.class, () ->
                    RetryingTempDirFactory.retryCleanup(() -> { throw failure; }, 3, 100)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertInstanceOf(InterruptedException.class, failure.getSuppressed()[0]);
        } finally {
            Thread.interrupted();
        }
    }
}
