package com.foldbackup.core.format;

import com.foldbackup.core.StreamingHash;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Ordered chunk events. Buffers are borrowed only for the duration of data(). */
public final class RegionStream {
    private RegionStream() {}
    @FunctionalInterface public interface Source { void read(Consumer consumer) throws IOException; }
    public interface Consumer {
        void begin(int index, int timestamp) throws IOException;
        /** Optional metadata for sources that know whether this cell has a stored chunk record. */
        default void present(boolean present) throws IOException {}
        void data(byte[] buffer, int offset, int length) throws IOException;
        void end() throws IOException;
    }
    public record Summary(int[] lengths, int[] timestamps, byte[] hash, long nbtBytes) {
        public boolean same(Summary other) {
            return Arrays.equals(lengths, other.lengths) && Arrays.equals(timestamps, other.timestamps)
                    && Arrays.equals(hash, other.hash) && nbtBytes == other.nbtBytes;
        }
    }
    public static class Accumulator implements Consumer {
        private final int[] lengths = new int[1024], timestamps = new int[1024];
        private final StreamingHash.Hash128 hash = new StreamingHash.Hash128();
        private int index = -1;
        private boolean active;
        private long total;
        public void begin(int next, int timestamp) throws IOException {
            if (active || next <= index || next >= 1024) throw new IOException("Invalid chunk event order");
            index = next; active = true; timestamps[index] = timestamp;
        }
        public void data(byte[] buffer, int offset, int length) throws IOException {
            if (!active || length < 0 || length > Integer.MAX_VALUE - lengths[index])
                throw new IOException("Invalid or oversized chunk");
            lengths[index] += length;
            total += length;
            hash.update(buffer, offset, length);
        }
        public void end() throws IOException {
            if (!active) throw new IOException("No active chunk");
            active = false;
        }
        public Summary finish() throws IOException {
            if (active) throw new IOException("Unfinished chunk");
            byte[] table = ByteBuffer.allocate(4096).array();
            ByteBuffer b = ByteBuffer.wrap(table);
            for (int length : lengths) b.putInt(length);
            // finish is called once by each core operation.
            hash.update(table, 0, table.length);
            return new Summary(lengths.clone(), timestamps.clone(), hash.digest(), total);
        }
    }
    public static Summary inspect(Source source) throws IOException {
        Accumulator accumulator = new Accumulator();
        source.read(accumulator);
        return accumulator.finish();
    }
    public static Source of(Region region) {
        return consumer -> {
            for (int i = 0; i < 1024; i++) {
                consumer.begin(i, region.getTimestamp(i));
                ChunkData chunk = region.getChunk(i);
                if (chunk != null) consumer.data(chunk.getRawChunk(), 0, chunk.getRawChunk().length);
                consumer.end();
            }
        };
    }
}
