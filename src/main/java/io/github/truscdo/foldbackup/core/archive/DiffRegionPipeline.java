package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.StreamingHash;
import io.github.truscdo.foldbackup.core.format.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Comparison and decoding for one {@code <dir>/diff.lrhd} frame, in Region/slot order.
 * <p>
 * Decoding the baseline produces no compressed bytes, so the single feeding thread would leave the
 * encoder's workers idle while it runs. The next baseline is therefore decoded ahead on one helper
 * thread, leaving the feeding thread to hand changed chunks straight to the encoder without pause;
 * Zstd's own native worker pool does the compressing, so there is no application-level compressor
 * thread. Back pressure comes from the encoder's internal job queue, and the chunk buffer spills
 * oversized chunks to disk instead of holding them in memory.
 */
final class DiffRegionPipeline {
    private static final int HEADER_CELLS = 1024;
    record RegionTask(int rx, int rz, RegionStream.Source base, RegionStream.Source target) {}

    static void run(List<RegionTask> tasks, long chunkLimit, Path scratch, LrhdEncodingWriter writer)
            throws IOException {
        if (tasks.isEmpty()) return;
        if (chunkLimit < 1) throw new IllegalArgumentException("Invalid diff chunk budget");
        ExecutorService prefetch = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "foldbackup-diff-baseline"));
        try {
            Future<String[]> pending = null;
            for (int i = 0; i < tasks.size(); i++) {
                StreamIo.checkCancelled();
                RegionTask task = tasks.get(i);
                // The first baseline has no predecessor to overlap with, so it is decoded inline.
                String[] baseline = pending == null ? baselineOf(task) : await(pending);
                // Submit before decoding the target so the two run concurrently.
                pending = null;
                if (i + 1 < tasks.size()) {
                    RegionTask next = tasks.get(i + 1);
                    pending = prefetch.submit(() -> baselineOf(next));
                }
                var region = new LrhdFormat.RegionIndex(); region.rx = task.rx; region.rz = task.rz;
                produce(task, baseline, chunkLimit, scratch, writer, region);
                writer.region(region);
            }
        } finally {
            prefetch.shutdownNow();
            // close joins even when the caller is interrupted, restoring its interrupt flag.
            prefetch.close();
        }
    }

    /** Baseline chunk hashes, or {@code null} when the Region has no baseline at all. */
    private static String[] baselineOf(RegionTask task) throws IOException {
        if (task.base() == null) return null;
        String[] hashes = new String[HEADER_CELLS];
        task.base().read(new RegionStream.Consumer() {
            int index; long length; StreamingHash.Hash128 hash;
            public void begin(int i, int timestamp) { index = i; length = 0; hash = new StreamingHash.Hash128(); }
            public void data(byte[] b, int off, int len) { hash.update(b, off, len); length += len; }
            public void end() { if (length > 0) hashes[index] = HashUtil.bytesToHex(hash.digest()); }
        });
        return hashes;
    }

    private static String[] await(Future<String[]> pending) throws IOException {
        try {
            return pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LRHD baseline prefetch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("LRHD baseline prefetch failed", cause);
        }
    }

    private static void produce(RegionTask task, String[] baseline, long chunkLimit, Path scratch,
            LrhdEncodingWriter writer, LrhdFormat.RegionIndex region) throws IOException {
        class TargetConsumer implements RegionStream.Consumer, AutoCloseable {
            int index, timestamp;
            DiffChunkBuffer payload;
            StreamingHash.Hash128 hash;
            public void begin(int i, int ts) {
                index = i; timestamp = ts;
                payload = new DiffChunkBuffer(scratch, chunkLimit); hash = new StreamingHash.Hash128();
            }
            public void data(byte[] b, int off, int len) throws IOException {
                payload.write(b, off, len); hash.update(b, off, len);
            }
            public void end() throws IOException {
                payload.seal();
                int length = payload.length();
                String actual = length == 0 ? null : HashUtil.bytesToHex(hash.digest());
                String previous = baseline == null ? null : baseline[index];
                if (Objects.equals(actual, previous)) { payload.close(); payload = null; return; }
                var chunk = new LrhdFormat.ChunkIndex();
                chunk.cx = Math.addExact(Math.multiplyExact(task.rx, 32), index % 32);
                chunk.cz = Math.addExact(Math.multiplyExact(task.rz, 32), index / 32);
                chunk.ts = timestamp; chunk.length = length; chunk.deleted = length == 0;
                chunk.hash = actual == null ? "" : actual;
                try (var data = payload) {
                    payload = null; // Ownership moves to the encoder, including on failure.
                    if (length > 0) data.compressTo(writer);
                }
                region.chunks.add(chunk);
            }
            public void close() throws IOException { if (payload != null) payload.close(); }
        }
        try (var consumer = new TargetConsumer()) { task.target.read(consumer); }
        // LRH read() has now verified its frame, footer and content hash.
    }

    private DiffRegionPipeline() {}
}
