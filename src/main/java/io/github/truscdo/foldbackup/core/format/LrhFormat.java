package io.github.truscdo.foldbackup.core.format;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Arrays;

/**
 * LRH v3: 51-byte header, uncompressed 8192-byte table, one NBT Zstd frame,
 * 8-byte footer. Each table cell is {@code [length i32][timestamp i32]} (big
 * endian, grid order) and the table sits outside the frame, so the frame
 * carries
 * chunk NBT only. The header content hash is
 * XXH3-128(NBT in grid order || big-endian length-only table).
 * <p>
 * Backup, archive storage, export and rollback use the streaming APIs
 * ({@link #write}, {@link #read}, {@link #recompress}).
 */
public final class LrhFormat {
    public static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;
    public static final byte LINEAR_VERSION = 3;
    public static final int PREHEADER_SIZE = 51, CONTENT_HASH_OFFSET = 35, CONTENT_HASH_SIZE = 16;
    public static final int HEADER_TABLE_SIZE = 8192, CONTENT_HEADER_TABLE_SIZE = 4096;
    public static final int HEADER_CELLS = 1024, FOOTER_SIZE = 8;
    public static final int TABLE_OFFSET = PREHEADER_SIZE;
    public static final int COMPRESSED_DATA_OFFSET = TABLE_OFFSET + HEADER_TABLE_SIZE;
    public static final int MIN_FILE_SIZE = COMPRESSED_DATA_OFFSET + FOOTER_SIZE;

    private LrhFormat() {
    }

    /**
     * Consumes source exactly once. Does not close or commit the caller's channel.
     */
    public static RegionStream.Summary write(SeekableByteChannel out, RegionStream.Source source, int level)
            throws IOException {
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE)
            throw new IOException("Invalid compression level");
        long start = out.position();
        StreamIo.write(out, new byte[COMPRESSED_DATA_OFFSET]);
        RegionStream.Summary summary = compress(StreamIo.output(out), source, level);
        finish(out, start, summary, level);
        return summary;
    }

    /** Emits only the Zstd frame. Finishes the frame before returning; does not close output. */
    public static RegionStream.Summary compress(OutputStream output, RegionStream.Source source, int level)
            throws IOException {
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE)
            throw new IOException("Invalid compression level");
        var accumulator = new RegionStream.Accumulator();
        // The destination owns buffering (a bounded block pool for archive creation).
        OutputStream borrowed = StreamIo.nonClosingOutput(output);
        try (var zstd = new ZstdOutputStream(borrowed, level)) {
            zstd.setChecksum(true);
            source.read(new RegionStream.Consumer() {
                public void begin(int index, int timestamp) throws IOException {
                    accumulator.begin(index, timestamp);
                }

                public void data(byte[] b, int off, int len) throws IOException {
                    accumulator.data(b, off, len);
                    zstd.write(b, off, len);
                }

                public void end() throws IOException {
                    accumulator.end();
                }
            });
        }
        return accumulator.finish();
    }

    /** Appends the footer and backfills the reserved header/table; restores the end position. */
    public static void finish(SeekableByteChannel out, long start, RegionStream.Summary summary, int level)
            throws IOException {
        long compressed = out.position() - start - COMPRESSED_DATA_OFFSET;
        if (compressed < 0 || compressed > 0xffffffffL)
            throw new IOException("LRH compressed length exceeds u32");
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE)
            throw new IOException("Invalid compression level");
        StreamIo.write(out, ByteBuffer.allocate(8).putLong(LINEAR_SIGNATURE).array());
        long end = out.position();
        int count = 0, newest = 0;
        ByteBuffer table = ByteBuffer.allocate(HEADER_TABLE_SIZE);
        for (int i = 0; i < HEADER_CELLS; i++) {
            int length = summary.lengths()[i], timestamp = summary.timestamps()[i];
            table.putInt(length).putInt(timestamp);
            if (length > 0) {
                count++;
                newest = Math.max(newest, timestamp);
            }
        }
        ByteBuffer header = ByteBuffer.allocate(PREHEADER_SIZE);
        header.putLong(LINEAR_SIGNATURE).put(LINEAR_VERSION).putLong(newest).putShort((short) level)
                .putInt(count).putInt((int) compressed).putLong(0).put(summary.hash());
        out.position(start);
        StreamIo.write(out, header.array());
        StreamIo.write(out, table.array());
        out.position(end);
    }

    public static RegionStream.Summary read(Path path, RegionStream.Consumer consumer) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in, Files.size(path), consumer);
        }
    }

    /**
     * Reads one bounded LRH file, validates its frame and content hash. Does not
     * close input.
     */
    public static RegionStream.Summary read(InputStream input, long size, RegionStream.Consumer consumer)
            throws IOException {
        if (size < MIN_FILE_SIZE)
            throw new IOException("File too short for LRH");
        DataInputStream in = new DataInputStream(input);
        byte[] header = new byte[PREHEADER_SIZE];
        in.readFully(header);
        if (ByteBuffer.wrap(header).getLong() != LINEAR_SIGNATURE || header[8] != LINEAR_VERSION)
            throw new IOException("Invalid LRH signature/version");
        long compressed = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(23));
        if (compressed != size - COMPRESSED_DATA_OFFSET - FOOTER_SIZE)
            throw new IOException("Invalid LRH frame length");
        byte[] table = new byte[HEADER_TABLE_SIZE];
        in.readFully(table);
        ByteBuffer entries = ByteBuffer.wrap(table);
        var accumulator = new RegionStream.Accumulator();
        byte[] buffer = new byte[65536];
        try (var zstd = new ZstdInputStream(StreamIo.limited(in, compressed))) {
            for (int i = 0; i < HEADER_CELLS; i++) {
                int length = entries.getInt(), timestamp = entries.getInt();
                if (length < 0)
                    throw new IOException("Unsupported LRH chunk length at " + i);
                accumulator.begin(i, timestamp);
                consumer.begin(i, timestamp);
                int remaining = length;
                while (remaining > 0) {
                    StreamIo.checkCancelled();
                    int n = zstd.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (n < 0)
                        throw new EOFException("Truncated LRH chunk at " + i);
                    accumulator.data(buffer, 0, n);
                    consumer.data(buffer, 0, n);
                    remaining -= n;
                }
                accumulator.end();
                consumer.end();
            }
            if (zstd.read() != -1)
                throw new IOException("LRH length table does not cover NBT frame");
        }
        if (in.readLong() != LINEAR_SIGNATURE)
            throw new IOException("Invalid LRH footer");
        var summary = accumulator.finish();
        if (!Arrays.equals(summary.hash(), Arrays.copyOfRange(header, CONTENT_HASH_OFFSET, PREHEADER_SIZE)))
            throw new IOException("Content hash mismatch");
        return summary;
    }

    public static RegionStream.Summary recompress(Path path, SeekableByteChannel out, int level) throws IOException {
        return write(out, consumer -> read(path, consumer), level);
    }

    public static RegionStream.Summary recompress(InputStream in, long size, SeekableByteChannel out, int level)
            throws IOException {
        return write(out, consumer -> read(in, size, consumer), level);
    }
}
