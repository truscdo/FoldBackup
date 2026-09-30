package com.foldbackup.core.archive;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal POSIX ustar tar writer for archives.
 * <p>
 * Java's standard library has no tar support; this implements the subset
 * needed for archives: regular files with relative paths, long names
 * via the ustar {@code prefix} field (up to 255 chars total).
 * <p>
 * Layout per entry: 512-byte header + data padded to a 512-byte boundary.
 * Archive ends with two 512-byte zero blocks.
 */
public final class TarWriter implements AutoCloseable {

    private static final int BLOCK = 512;
    private static final int NAME_LEN = 100;
    private static final int PREFIX_LEN = 155;

    private final OutputStream out;
    private final java.nio.channels.SeekableByteChannel channel;
    private boolean finished;

    public TarWriter(OutputStream out) {
        this.channel = null;
        // 1MB 缓冲：消除 header/padding 小写入的系统调用
        this.out = new BufferedOutputStream(out, 1 << 20);
    }

    public TarWriter(java.nio.channels.SeekableByteChannel channel) {
        this.channel = channel;
        this.out = com.foldbackup.core.format.StreamIo.output(channel);
    }

    @FunctionalInterface public interface EntryWriter {
        void write(java.nio.channels.SeekableByteChannel output) throws IOException;
    }

    /** Unknown-size entry. Both the entry header and its nested format may be backfilled. */
    public void putEntry(String name, EntryWriter writer) throws IOException {
        if (channel == null) throw new IOException("Unknown-size entries require seekable TAR output");
        if (finished || name.startsWith("/")) throw new IOException("Invalid TAR write");
        long header = channel.position();
        out.write(buildHeader(name, 0));
        long start = channel.position();
        writer.write(channel);
        long end = channel.position(), length = end - start;
        if (length < 0 || length > 077777777777L) throw new IOException("TAR entry size out of range");
        channel.position(header);
        out.write(buildHeader(name, length));
        channel.position(end);
        out.write(new byte[(int) ((BLOCK - length % BLOCK) % BLOCK)]);
    }

    public void putEntry(String name, java.io.InputStream input, long size) throws IOException {
        if (finished || name.startsWith("/") || size < 0 || size > 077777777777L) throw new IOException("Invalid TAR entry");
        out.write(buildHeader(name, size));
        byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            com.foldbackup.core.format.StreamIo.checkCancelled();
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new java.io.EOFException("Truncated TAR input: " + name);
            out.write(buffer, 0, n); remaining -= n;
        }
        if (input.read() != -1) throw new IOException("TAR input exceeds declared length: " + name);
        out.write(new byte[(int) ((BLOCK - size % BLOCK) % BLOCK)]);
    }

    /**
     * Write a regular file entry.
     *
     * @param name relative path using '/' separators (no leading '/')
     * @param data file contents
     */
    public void putEntry(String name, byte[] data) throws IOException {
        putEntry(name, data, 0, data.length);
    }

    /**
     * Write a regular file entry from a slice of {@code data}.
     */
    public void putEntry(String name, byte[] data, int offset, int length) throws IOException {
        if (finished) {
            throw new IOException("TarWriter already finished");
        }
        if (name.startsWith("/")) {
            throw new IOException("Tar entry name must be relative: " + name);
        }
        byte[] header = buildHeader(name, length);
        out.write(header);
        out.write(data, offset, length);
        // Pad to 512-byte boundary
        int padding = (BLOCK - (length % BLOCK)) % BLOCK;
        if (padding > 0) {
            out.write(new byte[padding]);
        }
    }

    /**
     * Write the two zero end-of-archive blocks. Idempotent.
     */
    public void finish() throws IOException {
        if (!finished) {
            out.write(new byte[BLOCK * 2]);
            out.flush();
            finished = true;
        }
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }

    private static byte[] buildHeader(String name, long size) throws IOException {
        byte[] header = new byte[BLOCK];

        // Split name into name (100) + prefix (155) if needed
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        String prefix = "";
        String baseName = name;
        if (nameBytes.length > NAME_LEN) {
            if (nameBytes.length > NAME_LEN + PREFIX_LEN) {
                throw new IOException("Tar entry name too long (>255 bytes): " + name);
            }
            // Split at a '/' boundary within the prefix limit
            int split = -1;
            for (int i = name.length() - 1; i >= 0; i--) {
                if (name.charAt(i) == '/') {
                    int prefixLen = i; // bytes before the slash
                    if (prefixLen <= PREFIX_LEN && name.length() - i - 1 <= NAME_LEN) {
                        split = i;
                        break;
                    }
                }
            }
            if (split < 0) {
                throw new IOException("Tar entry name cannot be split into prefix+name: " + name);
            }
            prefix = name.substring(0, split);
            baseName = name.substring(split + 1);
        }

        byte[] baseBytes = baseName.getBytes(StandardCharsets.UTF_8);
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(baseBytes, 0, header, 0, Math.min(baseBytes.length, NAME_LEN));
        System.arraycopy(prefixBytes, 0, header, 345, Math.min(prefixBytes.length, PREFIX_LEN));

        // mode (octal, 8 bytes)
        writeOctal(header, 100, 8, 0644);
        // uid / gid
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        // size (octal, 12 bytes)
        writeOctal(header, 124, 12, size);
        // mtime (octal, 12 bytes)
        writeOctal(header, 136, 12, 0);
        // chksum: 8 spaces initially, computed below
        Arrays.fill(header, 148, 156, (byte) ' ');
        // typeflag: '0' = regular file
        header[156] = '0';
        // magic "ustar\0" + version "00"
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);
        // uname / gname (empty)
        // devmajor / devminor (empty)

        // Checksum: sum of all header bytes (with chksum field as spaces)
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        // 6 octal digits + NUL + space
        String chk = String.format("%06o", sum) + "\0 ";
        byte[] chkBytes = chk.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chkBytes, 0, header, 148, chkBytes.length);

        return header;
    }

    private static void writeOctal(byte[] header, int offset, int len, long value) {
        String octal = String.format("%0" + (len - 1) + "o", value);
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + len - 1] = 0; // trailing NUL
    }
}
