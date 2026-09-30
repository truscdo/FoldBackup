package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.archive.*;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.store.*;
import java.nio.file.*;
import java.util.*;

/** End-to-end low-heap regression for selective rollback and full+delta restore. */
public final class StreamingRestoreMemoryProcess {
    private static final int LARGE = 96 << 20;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        BlobStore blobs = new BlobStore(root.resolve("store"), 3) {
            @Override public byte[] readLrh(String path) { throw new AssertionError("Whole LRH read"); }
        };
        SnapshotStore store = new SnapshotStore(blobs.getRootDir());
        byte[] small = EntityRegionCodec.empty(-1, -1).nbt();
        var before = source((byte) 1, small, true);
        var after = source((byte) 2, small, false);
        var base = snapshot(blobs, "S-20260912_100000", 1000, before);
        store.saveSnapshot(base);
        var resolved = ChunkRollbackSupport.resolveChunk(store, blobs, -1, -1, base.getEpochSeconds());
        if (!Arrays.equals(small, resolved.chunk().getRawChunk())) throw new AssertionError("Wrong rollback chunk");
        if (resolved.chunk().getX() != -1 || resolved.chunk().getZ() != -1) throw new AssertionError("Coordinates");
        var entities = ChunkRollbackSupport.resolveEntities(base, blobs, -1, -1, "");
        if (entities.state() != ChunkRollbackSupport.EntitySnapshotState.PRESENT || !entities.snapshot().isEmpty())
            throw new AssertionError("Wrong entity selection");
        if (ChunkRollbackSupport.resolveChunk(store, blobs, -2, -1, base.getEpochSeconds()).chunk() != null)
            throw new AssertionError("Missing chunk");

        var target = snapshot(blobs, "S-20260912_110000", 2000, after);
        // Also exercise a delta-only region containing an oversized chunk.
        var first = target.getRegions().getFirst();
        target.addRegion(new RegionEntry("region/r.1.0.mca", first.getContentHash(), first.getBlobPath(),
                first.getSizeCompressed(), first.getSizeUncompressed(), first.getSourceMetadata()));
        store.saveSnapshot(target);
        Path archive = root.resolve("archive"), output = root.resolve("restored");
        var exporter = new ArchiveCreator(store, blobs, 3);
        exporter.createFull(base.getEpochSeconds(), archive, false, 1);
        exporter.createDiff(base.getEpochSeconds(), target.getEpochSeconds(), archive);
        new ArchiveRestorer(6).restore(archive, base.getEpochSeconds(), target.getEpochSeconds(), output);
        var expected = RegionStream.inspect(after);
        for (String path : List.of("region/r.-1.-1.mca", "entities/r.-1.-1.mca", "region/r.1.0.mca")) {
            if (!expected.same(RegionStream.inspect(McaStream.source(output.resolve(path)))))
                throw new AssertionError("Restore mismatch: " + path);
        }
        // Corruption outside the requested cell must still reject a rollback read.
        Path blob = blobs.getRootDir().resolve(first.getBlobPath());
        try (var channel = java.nio.channels.FileChannel.open(blob, StandardOpenOption.WRITE)) {
            channel.position(LrhFormat.CONTENT_HASH_OFFSET);
            StreamIo.write(channel, new byte[16]);
        }
        try {
            ChunkRollbackSupport.resolveChunk(store, blobs, -1, -1, target.getEpochSeconds());
            throw new AssertionError("Unchecked LRH corruption");
        } catch (java.io.IOException expectedFailure) { /* expected */ }
        System.out.println("STREAM_RESTORE_OK mib=96 heap_max=" + Runtime.getRuntime().maxMemory());
    }

    private static RegionStream.Source source(byte value, byte[] small, boolean deletedLater) {
        return consumer -> {
            byte[] buffer = new byte[65536]; Arrays.fill(buffer, value);
            consumer.begin(0, value);
            for (int n = 0; n < LARGE / buffer.length; n++) consumer.data(buffer, 0, buffer.length);
            consumer.end();
            if (deletedLater) {
                consumer.begin(1, 7); consumer.data(small, 0, small.length); consumer.end();
            }
            consumer.begin(1023, 42); consumer.data(small, 0, small.length); consumer.end();
        };
    }

    private static SnapshotManifest snapshot(BlobStore blobs, String id, long time, RegionStream.Source source)
            throws Exception {
        var snapshot = new SnapshotManifest(id, time, "world");
        try (var session = blobs.newWriteSession()) {
            var summary = LrhFormat.write(session.channel(), source, 3);
            String hash = HashUtil.bytesToHex(summary.hash());
            var commit = session.commit(hash, "lrh");
            for (String dir : List.of("region", "entities"))
                snapshot.addRegion(new RegionEntry(dir + "/r.-1.-1.mca", hash, commit.path(), commit.size(),
                        summary.nbtBytes()));
        }
        return snapshot;
    }
}
