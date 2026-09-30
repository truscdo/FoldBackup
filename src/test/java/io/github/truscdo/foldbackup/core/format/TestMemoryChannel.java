package io.github.truscdo.foldbackup.core.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

/** Small seekable in-memory channel for tests of the production stream writers. */
final class TestMemoryChannel implements SeekableByteChannel {
    private byte[] bytes = new byte[16 * 1024];
    private int position;
    private int size;
    private boolean open = true;

    byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    @Override public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        if (position >= size) return -1;
        int length = Math.min(destination.remaining(), size - position);
        destination.put(bytes, position, length);
        position += length;
        return length;
    }

    @Override public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        int length = source.remaining();
        long end = (long) position + length;
        if (end > Integer.MAX_VALUE - 8) throw new IOException("Test byte array too large");
        if (end > bytes.length) {
            int capacity = (int) Math.max(end, Math.min(Integer.MAX_VALUE - 8L, bytes.length * 2L));
            bytes = Arrays.copyOf(bytes, capacity);
        }
        source.get(bytes, position, length);
        position += length;
        size = Math.max(size, position);
        return length;
    }

    @Override public long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0 || newPosition > Integer.MAX_VALUE - 8L)
            throw new IOException("Invalid test channel position");
        position = (int) newPosition;
        return this;
    }

    @Override public long size() throws IOException {
        ensureOpen();
        return size;
    }

    @Override public SeekableByteChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if (newSize < 0) throw new IllegalArgumentException("Negative size");
        size = (int) Math.min(size, newSize);
        position = Math.min(position, size);
        return this;
    }

    @Override public boolean isOpen() {
        return open;
    }

    @Override public void close() {
        open = false;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) throw new ClosedChannelException();
    }
}
