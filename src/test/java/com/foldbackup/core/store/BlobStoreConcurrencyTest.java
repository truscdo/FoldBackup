package com.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobStoreConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentProducersOfSameBlobDoNotShareWipFile() throws Exception {
        BlobStore store = new BlobStore(tempDir, 6);
        byte[] data = new byte[256 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(executor.submit(() -> store.storeLrh("ab1234567890abcd", data)));
            }

            for (Future<String> future : futures) {
                assertEquals("blobs/ab/ab1234567890abcd.lrh", future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertArrayEquals(data, store.readLrh("blobs/ab/ab1234567890abcd.lrh"));
        try (var files = Files.walk(tempDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".wip")));
        }
    }
}
