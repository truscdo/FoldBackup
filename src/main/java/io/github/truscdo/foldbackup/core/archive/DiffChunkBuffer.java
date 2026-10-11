package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.LrhdEncodingWriter;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One owned chunk. Unusually large chunks spill without decoding the source again. */
final class DiffChunkBuffer implements AutoCloseable {
    private final Path scratch;
    private final long memoryLimit;
    private final List<byte[]> blocks = new ArrayList<>();
    private byte[] tail;
    private int tailUsed, length;
    private Path spill;
    private OutputStream spillOutput;

    DiffChunkBuffer(Path scratch, long memoryLimit) { this.scratch = scratch; this.memoryLimit = memoryLimit; }
    int length() { return length; }

    void write(byte[] bytes, int offset, int count) throws IOException {
        if (count > Integer.MAX_VALUE - length) throw new IOException("LRHD chunk exceeds i32 length");
        if (spill == null && count > memoryLimit - length) {
            spill = Files.createTempFile(scratch, "lrhd-chunk-", ".nbt");
            spillOutput = new BufferedOutputStream(Files.newOutputStream(spill), 65536);
            for (byte[] block : blocks) spillOutput.write(block);
            if (tailUsed > 0) spillOutput.write(tail, 0, tailUsed);
            blocks.clear(); tail = null; tailUsed = 0;
        }
        if (spill != null) {
            spillOutput.write(bytes, offset, count); length += count; return;
        }
        while (count > 0) {
            if (tail == null) {
                int capacity = blocks.isEmpty() ? Math.min(65536, Math.max(256, count)) : 65536;
                tail = new byte[(int) Math.min(capacity, memoryLimit - length)];
            }
            int n = Math.min(count, tail.length - tailUsed);
            System.arraycopy(bytes, offset, tail, tailUsed, n);
            tailUsed += n; length += n; offset += n; count -= n;
            if (tailUsed == tail.length) { blocks.add(tail); tail = null; tailUsed = 0; }
        }
    }

    void seal() throws IOException {
        if (spillOutput != null) { spillOutput.close(); spillOutput = null; }
        if (tailUsed > 0) blocks.add(Arrays.copyOf(tail, tailUsed));
        tail = null; tailUsed = 0;
    }

    void compressTo(LrhdEncodingWriter writer) throws IOException {
        if (spill == null) writer.replacement(blocks);
        else try (var input = Files.newInputStream(spill)) { writer.replacement(input, length); }
    }

    @Override public void close() throws IOException {
        try { if (spillOutput != null) { spillOutput.close(); spillOutput = null; } }
        finally {
            blocks.clear(); tail = null;
            if (spill != null) { Files.deleteIfExists(spill); spill = null; }
        }
    }
}
