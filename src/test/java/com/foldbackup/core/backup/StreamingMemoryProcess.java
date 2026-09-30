package com.foldbackup.core.backup;

import com.foldbackup.core.format.*;
import com.foldbackup.core.store.*;
import java.nio.file.*;

/**
 * Standalone low-heap probe: source bytes are generated, never held as a whole.
 */
public final class StreamingMemoryProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        for (int mebibytes : new int[] { 8, 96 }) {
            long started = System.nanoTime();
            Path world = root.resolve("world-" + mebibytes);
            Files.createDirectories(world.resolve("region"));
            byte[] buffer = new byte[65536];
            new java.util.Random(1).nextBytes(buffer);
            int blocks = mebibytes * 16;
            Path ordinary = world.resolve("large.dat");
            try (var out = Files.newOutputStream(ordinary)) {
                for (int i = 0; i < blocks; i++)
                    out.write(buffer);
            }
            McaStreamWriter.write(world.resolve("region/r.0.0.mca"), 0, 0, 0, 6, consumer -> {
                consumer.begin(0, 1);
                for (int i = 0; i < blocks; i++)
                    consumer.data(buffer, 0, buffer.length);
                consumer.end();
            });
            BlobStore blobs = new BlobStore(root.resolve("store-" + mebibytes), 3);
            var processor = new RegionProcessor(blobs, 3, new MetadataIndex(), BackupFilter.defaults(), 1, 32);
            var result = processor.scanWorld(world, null);
            if (!result.getErrors().isEmpty() || result.getRegionCount() != 1 || result.getFileCount() != 1)
                throw new AssertionError(result.getErrors());
            var region = result.getRegions().getFirst();
            var summary = LrhFormat.read(blobs.getRootDir().resolve(region.getBlobPath()),
                    new RegionStream.Accumulator());
            if (summary.nbtBytes() != (long) mebibytes * 1024 * 1024)
                throw new AssertionError("Region length");
            long bytes;
            try (var input = blobs.openFile(result.getFiles().getFirst().getBlobPath())) {
                bytes = input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (bytes != summary.nbtBytes())
                throw new AssertionError("File length");
            long heapPeak = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            long gcCount = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
            System.out.println("STREAM_MEMORY_OK mib=" + mebibytes + " heap_max=" + Runtime.getRuntime().maxMemory()
                    + " heap_pool_peaks_sum=" + heapPeak + " gc_count=" + gcCount + " elapsed_ms="
                    + (System.nanoTime() - started) / 1_000_000);
        }
    }
}
