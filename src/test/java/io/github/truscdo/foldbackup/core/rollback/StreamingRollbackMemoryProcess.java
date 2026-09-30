package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.backup.BackupFilter;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.McaStreamWriter;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Low-heap end-to-end probe: rollback one cell in a region larger than the JVM
 * heap.
 */
public final class StreamingRollbackMemoryProcess {
    private static final int LARGE_CHUNKS = 24;
    private static final int CHUNK_BYTES = 4 << 20;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path world = root.resolve("world"), regionDir = world.resolve("region");
        Files.createDirectories(regionDir);
        byte[] buffer = new byte[65536];
        Arrays.fill(buffer, (byte) 42);
        Path mca = regionDir.resolve("r.0.0.mca");

        McaStreamWriter.write(mca, 0, 0, 1000, 3, source((byte) 1, buffer));
        BlobStore blobs = new BlobStore(root.resolve("store"), 3);
        SnapshotStore snapshots = new SnapshotStore(blobs.getRootDir());
        var scan = new RegionProcessor(blobs, 3, new MetadataIndex(), BackupFilter.defaults(), 1, 32)
                .scanWorld(world, null);
        if (!scan.getErrors().isEmpty() || scan.getRegionCount() != 1)
            throw new AssertionError("Initial backup failed: " + scan.getErrors());
        var snapshot = new SnapshotManifest("S-20260917_120000", 1790222400L, "world");
        snapshot.setRegions(scan.getRegions());
        snapshot.setExtraFiles(scan.getFiles());
        snapshots.saveSnapshot(snapshot);

        McaStreamWriter.write(mca, 0, 0, 2000, 3, source((byte) 2, buffer));
        var result = new ChunkRollbackCoordinator(snapshots, blobs, 3)
                .rollbackChunk(regionDir, 0, 0, snapshot.getEpochSeconds(), "");
        if (!result.success())
            throw new AssertionError("Rollback failed: " + result.message());

        var summary = RegionStream.inspect(McaStream.source(mca));
        if (summary.lengths()[0] != 1 || summary.timestamps()[0] <= 1)
            throw new AssertionError("Target chunk was not restored");
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        McaStream.read(mca, new RegionStream.Consumer() {
            boolean selected;

            public void begin(int index, int timestamp) {
                selected = index == 0;
            }

            public void data(byte[] bytes, int offset, int length) {
                if (selected)
                    target.write(bytes, offset, length);
            }

            public void end() {
            }
        });
        if (!Arrays.equals(target.toByteArray(), new byte[] { 1 }))
            throw new AssertionError("Wrong target content");
        for (int i = 1; i <= LARGE_CHUNKS; i++)
            if (summary.lengths()[i] != CHUNK_BYTES)
                throw new AssertionError("Neighbor changed at " + i);
        System.out.println("STREAM_ROLLBACK_OK region_mib=" + ((long) LARGE_CHUNKS * CHUNK_BYTES >> 20)
                + " heap_max=" + Runtime.getRuntime().maxMemory());
    }

    private static RegionStream.Source source(byte target, byte[] buffer) {
        return consumer -> {
            for (int index = 0; index < 1024; index++) {
                consumer.begin(index, index == 0 ? 1 : index);
                if (index == 0)
                    consumer.data(new byte[] { target }, 0, 1);
                else if (index <= LARGE_CHUNKS) {
                    for (int n = 0; n < CHUNK_BYTES / buffer.length; n++)
                        consumer.data(buffer, 0, buffer.length);
                }
                consumer.end();
            }
        };
    }
}
