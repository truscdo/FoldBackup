package io.github.truscdo.foldbackup.core.format;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/** Bounded, non-owning adapters; callers retain ownership of channels. */
public final class StreamIo {
    private StreamIo() {}
    public static void write(SeekableByteChannel channel, byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) { checkCancelled(); IoBudget.call(() -> channel.write(b)); }
    }
    public static byte[] read(SeekableByteChannel channel, int length) throws IOException {
        byte[] bytes = new byte[length];
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) {
            checkCancelled();
            if (IoBudget.call(() -> channel.read(b)) < 0) throw new EOFException();
        }
        return bytes;
    }
    public static InputStream limited(InputStream in, long length) {
        return new FilterInputStream(in) {
            private long remaining = length;
            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
            }
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (remaining == 0) return -1;
                checkCancelled();
                int n = IoBudget.call(() -> in.read(b, off, (int) Math.min(len, remaining)));
                if (n < 0) throw new EOFException("Truncated bounded input");
                remaining -= n;
                return n;
            }
            public void close() {} // non-owning
        };
    }
    static OutputStream nonClosingOutput(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int off, int len) throws IOException {
                out.write(bytes, off, len);
            }
            @Override public void close() throws IOException { flush(); }
        };
    }
    public static OutputStream output(SeekableByteChannel channel) {
        return new OutputStream() {
            public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
            public void write(byte[] bytes, int off, int len) throws IOException {
                ByteBuffer b = ByteBuffer.wrap(bytes, off, len);
                writeFully(channel, b);
            }
        };
    }
    private static void writeFully(SeekableByteChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) { checkCancelled(); IoBudget.call(() -> channel.write(bytes)); }
    }
    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Operation cancelled");
    }
}
