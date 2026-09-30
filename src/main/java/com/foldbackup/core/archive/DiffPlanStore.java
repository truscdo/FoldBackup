package com.foldbackup.core.archive;

import com.foldbackup.core.HashUtil;
import com.foldbackup.core.format.*;
import com.foldbackup.core.store.RegionEntry;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** One task-owned spool; only lightweight references survive collection. */
final class DiffPlanStore implements AutoCloseable {
    record Ref(RegionEntry source, int rx, int rz, long offset, int length) {}
    private final FileChannel channel;

    DiffPlanStore(Path directory) throws IOException {
        channel = FileChannel.open(directory.resolve("diff-plans.bin"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /** Called by the coordinator, never by comparison workers. */
    Ref append(StreamingDiff.Plan plan) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        int count = 0;
        for (boolean selected : plan.selected()) if (selected) count++;
        output.writeInt(count);
        for (int i = 0; i < 1024; i++) if (plan.selected()[i]) {
            output.writeInt(i);
            output.writeInt(plan.lengths()[i]);
            output.writeInt(plan.timestamps()[i]);
            if (plan.lengths()[i] > 0) output.write(HashUtil.hexToBytes(plan.hashes()[i]));
        }
        long offset = channel.position();
        StreamIo.write(channel, bytes.toByteArray());
        int[] coords = AnvilFormat.parseRegionCoords(Path.of(plan.region().getFilename()).getFileName().toString());
        return new Ref(plan.region(), coords[0], coords[1], offset, bytes.size());
    }

    /** Positional reads allow producers and the archive writer to traverse independently. */
    LrhdFormat.RegionIndex read(Ref ref) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(ref.length());
        while (buffer.hasRemaining()) {
            StreamIo.checkCancelled();
            long position = ref.offset() + buffer.position();
            if (IoBudget.call(() -> channel.read(buffer, position)) < 0) throw new EOFException("Truncated diff plan");
        }
        var input = new DataInputStream(new ByteArrayInputStream(buffer.array()));
        var region = new LrhdFormat.RegionIndex();
        region.rx = ref.rx(); region.rz = ref.rz();
        int count = input.readInt();
        if (count < 1 || count > 1024) throw new IOException("Invalid diff plan chunk count");
        for (int i = 0; i < count; i++) {
            int slot = input.readInt();
            if (slot < 0 || slot >= 1024) throw new IOException("Invalid diff plan slot");
            var chunk = new LrhdFormat.ChunkIndex();
            chunk.cx = ref.rx() * 32 + slot % 32;
            chunk.cz = ref.rz() * 32 + slot / 32;
            chunk.length = input.readInt();
            chunk.ts = input.readInt();
            chunk.deleted = chunk.length == 0;
            if (chunk.deleted) chunk.hash = "";
            else {
                byte[] hash = new byte[16]; input.readFully(hash);
                chunk.hash = HashUtil.bytesToHex(hash);
            }
            region.chunks.add(chunk);
        }
        if (input.available() != 0) throw new IOException("Trailing diff plan bytes");
        return region;
    }

    @Override public void close() throws IOException { channel.close(); }
}
