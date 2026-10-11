package io.github.truscdo.foldbackup.core.archive;

import com.github.luben.zstd.ZstdInputStream;
import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.StreamingHash;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;

/** Real comparison and single-directory export with an index larger than the child JVM heap. */
public final class DiffPlanMemoryProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var blobs = new BlobStore(root.resolve("store"), 1);
        RegionEntry sample;
        try (var session = blobs.newWriteSession()) {
            var summary = LrhFormat.write(session.channel(), consumer -> {
                byte[] data = {42};
                for (int i = 0; i < 1024; i++) {
                    consumer.begin(i, 123);
                    consumer.data(data, 0, 1);
                    consumer.end();
                }
            }, 1);
            String hash = HashUtil.bytesToHex(summary.hash());
            var commit = session.commit(hash, "lrh");
            sample = new RegionEntry("", hash, commit.path(), commit.size(), summary.nbtBytes());
        }
        int count = 1200;
        var base = new SnapshotManifest("S-20260917_100000", 1000, "world");
        var target = new SnapshotManifest("S-20260917_110000", 2000, "world");
        for (int rx = count - 1; rx >= 0; rx--) target.addRegion(new RegionEntry("region/r." + rx + ".0.mca",
                sample.getContentHash(), sample.getBlobPath(), sample.getSizeCompressed(),
                sample.getSizeUncompressed(), sample.getSourceMetadata()));
        Path archive = root.resolve("diff.tar"), scratchPath;
        try (var scratch = new ArchiveTemporaryDirectory()) {
            scratchPath = scratch.path;
            try (var diff = new StreamingDiff(blobs, scratch.path)) {
                diff.compare(base, target, new ProgressTracker(null));
                try (var channel = FileChannel.open(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        var tar = new TarWriter(channel)) {
                    diff.writeDirectories(tar, 1, 8, new ProgressTracker(null));
                }
            }
        }
        if (Files.exists(scratchPath)) throw new AssertionError("Plan spool leaked");
        if (Files.size(archive) <= Runtime.getRuntime().maxMemory())
            throw new AssertionError("Probe index does not exceed heap");
        // Verify hash and payload without loading the entire dynamic index into memory.
        var archiveIndex = TarIndex.read(archive);
        if (archiveIndex.entries.size() != 1) throw new AssertionError("Unexpected entries");
        try (var input = new BufferedInputStream(
                LrhdLayoutSource.open(archiveIndex.entries.values().iterator().next()), 65536)) {
            byte[] header = input.readNBytes(LrhdFormat.HEADER_SIZE);
            ByteBuffer fields = ByteBuffer.wrap(header);
            if (fields.getLong() != LrhdFormat.SIGNATURE) throw new AssertionError("Signature");
            fields.position(12);
            if (fields.getInt() != count || fields.getInt() != count * 1024) throw new AssertionError("Counts");
            long raw = fields.getLong(), compressed = fields.getLong();
            byte[] expectedHash = Arrays.copyOfRange(header, LrhdFormat.METADATA_HASH_OFFSET, header.length);
            Arrays.fill(header, LrhdFormat.METADATA_HASH_OFFSET, header.length, (byte) 0);
            var hash = new StreamingHash.Hash128();
            hash.update(header, 0, header.length);
            long remaining = (long) count * (LrhdFormat.REGION_INDEX_SIZE + 1024 * LrhdFormat.CHUNK_INDEX_RECORD_SIZE);
            byte[] buffer = new byte[65536];
            while (remaining > 0) {
                int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new EOFException();
                hash.update(buffer, 0, n); remaining -= n;
            }
            if (!Arrays.equals(expectedHash, hash.digest())) throw new AssertionError("Metadata hash");
            long decoded = 0;
            try (var zstd = new ZstdInputStream(StreamIo.limited(input, compressed))) {
                int n;
                while ((n = zstd.read(buffer)) != -1) {
                    for (int i = 0; i < n; i++) if (buffer[i] != 42) throw new AssertionError("NBT changed");
                    decoded += n;
                }
            }
            if (decoded != raw || raw != (long) count * 1024) throw new AssertionError("Data length");
            if (new DataInputStream(input).readLong() != LrhdFormat.SIGNATURE) throw new AssertionError("Footer");
        }
        System.out.println("DIFF_PLAN_MEMORY_OK regions=" + count);
    }
}
