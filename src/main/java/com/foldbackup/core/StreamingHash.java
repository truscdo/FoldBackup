package com.foldbackup.core;

import com.dynatrace.hash4j.hashing.Hashing;
import com.dynatrace.hash4j.hashing.HashStream128;
import com.dynatrace.hash4j.hashing.HashStream64;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Incremental XXH3; preserves the two historical encodings used by the repository. */
public final class StreamingHash {
    private StreamingHash() {}
    public static final class Hash128 {
        private final HashStream128 state = Hashing.xxh3_128().hashStream();
        public void reset() { state.reset(); }
        public void update(byte[] bytes, int offset, int length) { state.putBytes(bytes, offset, length); }
        public byte[] digest() {
            var value = state.get();
            return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value.getLeastSignificantBits()).putLong(value.getMostSignificantBits()).array();
        }
        public String fileHex() {
            var value = state.get();
            return String.format("%016x%016x", value.getLeastSignificantBits(), value.getMostSignificantBits());
        }
    }
    public static final class Hash64 {
        private final HashStream64 state;
        public Hash64(long seed) { state = Hashing.xxh3_64(seed).hashStream(); }
        public void reset() { state.reset(); }
        public void update(byte[] bytes, int offset, int length) { state.putBytes(bytes, offset, length); }
        public long digest() { return state.getAsLong(); }
    }
    public static long read(InputStream in, Hash128 hash) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int n; (n = in.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            hash.update(buffer, 0, n);
            total = Math.addExact(total, n);
        }
        return total;
    }
}
