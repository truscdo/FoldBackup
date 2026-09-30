package com.foldbackup.core.archive;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal POSIX ustar tar reader for archives.
 * <p>
 * Companion to {@link TarWriter}. Supports regular files with ustar
 * prefix-based long names. Skips unknown entry types (directories, etc.).
 */
public final class TarReader implements AutoCloseable {

    private static final int BLOCK = 512;

    private final InputStream in;
    private Entry current;
    private long remaining; // bytes left in the current entry's data
    private boolean paddingPending;
    private boolean eof;

    public TarReader(InputStream in) {
        this.in = in;
    }

    /**
     * A single tar entry header.
     */
    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean isFile;

        Entry(String name, long size, boolean isFile) {
            this.name = name;
            this.size = size;
            this.isFile = isFile;
        }
    }

    /**
     * Advance to the next entry.
     *
     * @return the next entry, or {@code null} at end of archive
     */
    public Entry nextEntry() throws IOException {
        // Skip any remaining data of the previous entry
        if (current != null && remaining > 0) {
            skipFully(remaining);
        }
        finishPadding();
        current = null;
        remaining = 0;

        byte[] header = new byte[BLOCK];
        int read = readFully(header);
        if (read == 0) {
            eof = true;
            return null;
        }
        if (read < BLOCK) {
            throw new IOException("Truncated tar header");
        }

        // End-of-archive: two zero blocks (or one, tolerate)
        if (isZeroBlock(header)) {
            // Consume the second zero block if present
            byte[] second = new byte[BLOCK];
            int r2 = readFully(second);
            if (r2 > 0 && !isZeroBlock(second)) {
                throw new IOException("Garbage after end-of-archive marker");
            }
            eof = true;
            return null;
        }

        // Validate magic
        byte[] magic = Arrays.copyOfRange(header, 257, 263);
        if (!Arrays.equals(magic, "ustar\0".getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Not a ustar archive (bad magic)");
        }

        // Verify checksum
        long stored = parseOctal(header, 148, 8);
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            byte b = header[i];
            if (i >= 148 && i < 156) {
                b = (byte) ' ';
            }
            sum += b & 0xFF;
        }
        if (stored != sum) {
            throw new IOException("Tar header checksum mismatch for entry");
        }

        String name = readString(header, 0, NAME_LEN);
        String prefix = readString(header, 345, PREFIX_LEN);
        String fullName = prefix.isEmpty() ? name : prefix + "/" + name;

        long size = parseOctal(header, 124, 12);
        char type = (char) (header[156] & 0xFF);
        boolean isFile = (type == '0' || type == '\0');

        current = new Entry(fullName, size, isFile);
        remaining = size;
        paddingPending = true;
        return current;
    }

    /**
     * Read the full data of the current entry.
     *
     * @return entry data bytes
     */
    public byte[] readEntryData() throws IOException {
        if (current == null) {
            throw new IOException("No current tar entry");
        }
        if (remaining > Integer.MAX_VALUE) {
            throw new IOException("Entry too large to read into memory: " + current.name);
        }
        byte[] data = new byte[(int) remaining];
        if (readFully(data) != data.length)
            throw new EOFException("Truncated TAR entry");
        remaining = 0;
        // Skip padding to next 512 boundary
        finishPadding();
        return data;
    }

    /**
     * Read the data of the current entry into a stream (for large entries).
     */
    public void readEntryDataTo(java.io.OutputStream sink) throws IOException {
        if (current == null) {
            throw new IOException("No current tar entry");
        }
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                throw new EOFException("Unexpected EOF in tar entry " + current.name);
            }
            sink.write(buf, 0, n);
            remaining -= n;
        }
        finishPadding();
    }

    /**
     * Bounded entry stream; nextEntry drains leftovers and padding. Does not own
     * the archive.
     */
    public InputStream entryStream() throws IOException {
        if (current == null)
            throw new IOException("No current TAR entry");
        Entry entry = current;
        return new InputStream() {
            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
            }

            public int read(byte[] b, int off, int len) throws IOException {
                if (entry != current)
                    throw new IOException("Expired TAR entry stream");
                if (len == 0)
                    return 0;
                if (remaining == 0)
                    return -1;
                com.foldbackup.core.format.StreamIo.checkCancelled();
                int n = in.read(b, off, (int) Math.min(len, remaining));
                if (n < 0)
                    throw new EOFException("Truncated TAR entry");
                remaining -= n;
                return n;
            }
        };
    }

    private void finishPadding() throws IOException {
        if (paddingPending) {
            skipFully((BLOCK - current.size % BLOCK) % BLOCK);
            paddingPending = false;
        }
    }

    public boolean isEof() {
        return eof;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ── helpers ──

    private static final int NAME_LEN = 100;
    private static final int PREFIX_LEN = 155;

    private int readFully(byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private void skipFully(long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                byte[] tmp = new byte[(int) Math.min(8192, n - skipped)];
                int r = in.read(tmp);
                if (r < 0) {
                    throw new EOFException("Unexpected EOF in tar");
                }
                skipped += r;
            } else {
                skipped += s;
            }
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static long parseOctal(byte[] header, int offset, int len) {
        long value = 0;
        for (int i = offset; i < offset + len; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                continue;
            }
            if (b < '0' || b > '7') {
                break;
            }
            value = (value << 3) | (b - '0');
        }
        return value;
    }

    private static String readString(byte[] header, int offset, int len) {
        int end = offset;
        while (end < offset + len && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }
}
