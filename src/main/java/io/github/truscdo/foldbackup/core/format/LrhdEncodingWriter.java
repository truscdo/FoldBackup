package io.github.truscdo.foldbackup.core.format;

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import io.github.truscdo.foldbackup.core.StreamingHash;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

/** Append-only encoding layout: signature, frame, index, metadata, signature. */
public final class LrhdEncodingWriter implements AutoCloseable {
    private final OutputStream output;
    private final FileChannel index;
    private final int level, workers;
    private ZstdOutputStreamNoFinalizer zstd;
    private long compressed, dataLength, indexedLength;
    private int regions, chunks, previousX, previousZ;
    private boolean finished;

    /** The caller owns output and the index spool path. */
    public LrhdEncodingWriter(OutputStream output, Path indexPath, int level, int workers) throws IOException {
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE || workers < 1)
            throw new IOException("Invalid LRHD compression settings");
        this.output = output; this.level = level; this.workers = workers;
        index = FileChannel.open(indexPath, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try { new DataOutputStream(output).writeLong(LrhdFormat.SIGNATURE); }
        catch (IOException | RuntimeException | Error e) { index.close(); throw e; }
    }

    /** Blocks are borrowed until this call returns; Zstd owns any asynchronous job buffers. */
    private void beginData() throws IOException {
        if (finished) throw new IOException("LRHD encoding already finished");
        if (zstd == null) {
            OutputStream counted = new FilterOutputStream(StreamIo.nonClosingOutput(output)) {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len); compressed = Math.addExact(compressed, len);
                }
            };
            zstd = new ZstdOutputStreamNoFinalizer(counted, level);
            zstd.setWorkers(workers);
            zstd.setChecksum(true);
        }
    }

    public void replacement(List<byte[]> blocks) throws IOException {
        beginData();
        for (byte[] block : blocks) {
            StreamIo.checkCancelled();
            zstd.write(block);
            dataLength = Math.addExact(dataLength, block.length);
        }
    }

    /** Stream an oversized, already-compared chunk from its bounded disk spool. */
    public void replacement(InputStream input, long length) throws IOException {
        if (length <= 0 || length > Integer.MAX_VALUE) throw new IOException("Invalid LRHD replacement length");
        beginData();
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
            StreamIo.checkCancelled();
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new EOFException("Truncated LRHD chunk spool");
            zstd.write(buffer, 0, n); remaining -= n;
            dataLength = Math.addExact(dataLength, n);
        }
        if (input.read() != -1) throw new IOException("Trailing LRHD chunk spool bytes");
    }

    /** Record a completed Region, retaining no directory-sized metadata in memory. */
    public void region(LrhdFormat.RegionIndex region) throws IOException {
        if (finished) throw new IOException("LRHD encoding already finished");
        if (region.chunks.isEmpty()) return;
        if (regions > 0) LrhdFormat.validateRegionAfter(previousX, previousZ, region.rx, region.rz);
        LrhdFormat.writeIndex(index, List.of(region));
        for (var chunk : region.chunks) indexedLength = Math.addExact(indexedLength, chunk.length);
        regions = Math.addExact(regions, 1);
        chunks = Math.addExact(chunks, region.chunks.size());
        previousX = region.rx; previousZ = region.rz;
    }

    public int finish() throws IOException {
        if (finished) return regions;
        if (dataLength != indexedLength) throw new IOException("LRHD index does not cover encoded NBT");
        if (zstd != null) { zstd.close(); zstd = null; }
        byte[] header = ByteBuffer.allocate(LrhdFormat.HEADER_SIZE)
                .putLong(LrhdFormat.SIGNATURE).put(LrhdFormat.VERSION).put((byte) 0)
                .putShort((short) level).putInt(regions).putInt(chunks)
                .putLong(dataLength).putLong(compressed).put(new byte[LrhdFormat.METADATA_HASH_SIZE]).array();
        var hash = new StreamingHash.Hash128();
        hash.update(header, 0, header.length);
        index.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        while (index.read(buffer) != -1) {
            StreamIo.checkCancelled();
            int n = buffer.position();
            hash.update(buffer.array(), 0, n);
            output.write(buffer.array(), 0, n);
            buffer.clear();
        }
        System.arraycopy(hash.digest(), 0, header, LrhdFormat.METADATA_HASH_OFFSET, LrhdFormat.METADATA_HASH_SIZE);
        output.write(header, LrhdFormat.SIGNATURE_SIZE, LrhdFormat.METADATA_SIZE);
        new DataOutputStream(output).writeLong(LrhdFormat.SIGNATURE);
        output.flush();
        finished = true;
        return regions;
    }

    @Override public void close() throws IOException {
        try { if (zstd != null) { zstd.close(); zstd = null; } }
        finally { index.close(); }
    }
}
