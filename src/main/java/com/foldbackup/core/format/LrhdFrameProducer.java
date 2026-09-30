package com.foldbackup.core.format;

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import com.foldbackup.core.HashUtil;
import com.foldbackup.core.StreamingHash;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Produces a single LRHD Zstd frame from one Region plan at a time. */
public final class LrhdFrameProducer implements AutoCloseable {
    public record Summary(int regions, int chunks, long dataLength) {}
    private final OutputStream output;
    private final int level;
    private ZstdOutputStreamNoFinalizer zstd;
    private long dataLength;
    private int regionCount, chunkCount, previousRx, previousRz;
    private boolean finished;

    public LrhdFrameProducer(OutputStream output, int level) throws IOException {
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE) throw new IOException("Invalid compression level");
        this.output = output; this.level = level;
    }

    public void region(LrhdFormat.RegionIndex planned, RegionStream.Source source) throws IOException {
        if (finished) throw new IOException("LRHD frame already finished");
        if (source == null) throw new IOException("Null LRHD source");
        LrhdFormat.indexLength(List.of(planned));
        if (regionCount > 0) LrhdFormat.validateRegionAfter(previousRx, previousRz, planned.rx, planned.rz);
        previousRx = planned.rx; previousRz = planned.rz;
        var consumer = new RegionStream.Consumer() {
            int index = -1;
            int plannedIndex;
            long remaining;
            boolean active;
            LrhdFormat.ChunkIndex chunk;
            StreamingHash.Hash128 hash;

            public void begin(int i, int timestamp) throws IOException {
                if (active || i != index + 1 || i >= 1024)
                    throw new IOException("Invalid LRHD source grid order");
                active = true; index = i; chunk = null;
                while (plannedIndex < planned.chunks.size()
                        && planned.chunks.get(plannedIndex).slotIndex() < i)
                    throw new IOException("LRHD source omitted a planned chunk");
                if (plannedIndex >= planned.chunks.size()
                        || planned.chunks.get(plannedIndex).slotIndex() != i) return;
                chunk = planned.chunks.get(plannedIndex++);
                if (chunk.ts != timestamp)
                    throw new IOException("LRHD source timestamp differs from its metadata plan");
                remaining = chunk.length;
                hash = new StreamingHash.Hash128();
                if (!chunk.deleted) {
                    if (zstd == null) {
                        OutputStream buffered = new BufferedOutputStream(StreamIo.nonClosingOutput(output), 65536);
                        zstd = new ZstdOutputStreamNoFinalizer(buffered, level);
                        zstd.setWindowLog(LrhdFormat.DEFAULT_WINDOW_LOG);
                        zstd.setChecksum(true);
                    }
                    chunk.offset = dataLength;
                }
            }

            public void data(byte[] bytes, int off, int len) throws IOException {
                if (!active) throw new IOException("LRHD source data outside chunk");
                if (chunk == null) return;
                if (len < 0 || len > remaining)
                    throw new IOException("LRHD source changed at chunk " + index);
                if (len == 0) return;
                remaining -= len;
                hash.update(bytes, off, len);
                zstd.write(bytes, off, len);
                try {
                    dataLength = Math.addExact(dataLength, len);
                } catch (ArithmeticException e) {
                    throw new IOException("LRHD data length overflow", e);
                }
            }

            public void end() throws IOException {
                if (!active) throw new IOException("LRHD source ended outside chunk");
                active = false;
                if (chunk == null) return;
                if (remaining != 0)
                    throw new IOException("LRHD source changed at chunk " + index);
                if (!chunk.deleted) {
                    String actual = HashUtil.bytesToHex(hash.digest());
                    if (!actual.equals(chunk.hash))
                        throw new IOException("LRHD source hash differs at chunk " + index);
                }
            }
        };
        source.read(consumer);
        if (consumer.active || consumer.index != 1023 || consumer.plannedIndex != planned.chunks.size())
            throw new IOException("Incomplete LRHD source grid");
        regionCount = Math.addExact(regionCount, 1);
        chunkCount = Math.addExact(chunkCount, planned.chunks.size());
    }

    public Summary finish() throws IOException {
        if (!finished) {
            if (zstd != null) { zstd.close(); zstd = null; }
            finished = true;
        }
        return new Summary(regionCount, chunkCount, dataLength);
    }

    @Override public void close() throws IOException {
        if (zstd != null) { zstd.close(); zstd = null; }
    }
}
