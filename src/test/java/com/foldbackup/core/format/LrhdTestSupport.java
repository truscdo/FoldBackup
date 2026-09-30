package com.foldbackup.core.format;

import com.foldbackup.core.HashUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Array adapters retained only for LRHD format tests and fixtures. */
public final class LrhdTestSupport {
    private LrhdTestSupport() {}

    public static final class ChunkEntry {
        public final int rx, rz, cx, cz;
        public final long ts;
        public final byte[] nbt;

        public ChunkEntry(int rx, int rz, int cx, int cz, long ts, byte[] nbt) {
            this.rx = rx;
            this.rz = rz;
            this.cx = cx;
            this.cz = cz;
            this.ts = ts;
            this.nbt = nbt;
        }

        private int slotIndex() { return (cz & 31) * 32 + (cx & 31); }
    }

    public static final class LrhdFile {
        public final int compressionLevel;
        public final List<LrhdFormat.RegionIndex> regions;
        private final byte[] dataArea;

        private LrhdFile(LrhdFormat.Index index, byte[] dataArea) {
            compressionLevel = index.header().compressionLevel();
            regions = index.regions();
            this.dataArea = dataArea;
        }

        public List<LrhdFormat.ChunkIndex> allChunks() {
            return regions.stream().flatMap(region -> region.chunks.stream()).toList();
        }

        public byte[] chunkNbt(LrhdFormat.ChunkIndex chunk) {
            return chunk.deleted ? null : Arrays.copyOfRange(dataArea, (int) chunk.offset,
                    (int) (chunk.offset + chunk.length));
        }

        public LrhdFormat.ChunkIndex findChunk(int rx, int rz, int cx, int cz) {
            for (var region : regions) {
                if (region.rx != rx || region.rz != rz) continue;
                for (var chunk : region.chunks) {
                    if (chunk.cx == cx && chunk.cz == cz) return chunk;
                }
            }
            return null;
        }
    }

    public static byte[] write(int level, List<ChunkEntry> entries) throws IOException {
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE) throw new IOException("Invalid compression level");
        List<ChunkEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt((ChunkEntry chunk) -> chunk.rx)
                .thenComparingInt(chunk -> chunk.rz).thenComparingInt(ChunkEntry::slotIndex));

        List<LrhdFormat.RegionIndex> plans = new ArrayList<>();
        List<List<ChunkEntry>> groups = new ArrayList<>();
        for (ChunkEntry chunk : sorted) {
            LrhdFormat.validateCoordinates(chunk.rx, chunk.rz);
            LrhdFormat.validateTimestamp(chunk.ts);
            if ((chunk.cx >> 5) != chunk.rx || (chunk.cz >> 5) != chunk.rz)
                throw new IOException("Invalid LRHD chunk coordinates");
            if (chunk.nbt != null && chunk.nbt.length == 0) throw new IOException("Empty LRHD replacement");
            if (groups.isEmpty() || !sameRegion(groups.getLast().getFirst(), chunk))
                groups.add(new ArrayList<>());
            groups.getLast().add(chunk);
        }

        for (List<ChunkEntry> group : groups) {
            var region = new LrhdFormat.RegionIndex();
            region.rx = group.getFirst().rx;
            region.rz = group.getFirst().rz;
            for (ChunkEntry source : group) {
                var chunk = new LrhdFormat.ChunkIndex();
                chunk.cx = source.cx;
                chunk.cz = source.cz;
                chunk.ts = source.ts;
                chunk.deleted = source.nbt == null;
                chunk.length = chunk.deleted ? 0 : source.nbt.length;
                chunk.hash = chunk.deleted ? "" : HashUtil.bytesToHex(HashUtil.xxHash128(source.nbt));
                if (!region.chunks.isEmpty()
                        && region.chunks.getLast().slotIndex() >= chunk.slotIndex())
                    throw new IOException("Duplicate or unsorted LRHD chunk");
                region.chunks.add(chunk);
            }
            plans.add(region);
        }

        var output = new TestMemoryChannel();
        var archive = new LrhdStreamWriter.ArchiveWriter(output, level,
                visitor -> { for (var region : plans) visitor.accept(region); });
        try (var frame = new LrhdFrameProducer(StreamIo.output(output), level)) {
            for (int regionIndex = 0; regionIndex < groups.size(); regionIndex++) {
                List<ChunkEntry> group = groups.get(regionIndex);
                ChunkEntry[] slots = new ChunkEntry[1024];
                for (ChunkEntry chunk : group) {
                    int slot = chunk.slotIndex();
                    if (slots[slot] != null) throw new IOException("Duplicate LRHD chunk");
                    slots[slot] = chunk;
                }
                RegionStream.Source source = consumer -> {
                    for (int slot = 0; slot < slots.length; slot++) {
                        ChunkEntry chunk = slots[slot];
                        consumer.begin(slot, chunk == null ? 0 : (int) chunk.ts);
                        if (chunk != null && chunk.nbt != null)
                            consumer.data(chunk.nbt, 0, chunk.nbt.length);
                        consumer.end();
                    }
                };
                frame.region(plans.get(regionIndex), source);
            }
            archive.finish(frame.finish());
        }
        return output.toByteArray();
    }

    public static LrhdFile read(byte[] bytes) throws IOException {
        var input = new ByteArrayInputStream(bytes);
        LrhdFormat.Index index = LrhdFormat.readIndex(input, bytes.length);
        if (index.header().dataLength() > Integer.MAX_VALUE - 8)
            throw new IOException("LRHD too large for test array reader");
        var dataArea = new ByteArrayOutputStream();
        try (var reader = new LrhdFormat.DataReader(input, index)) {
            RegionStream.Consumer sink = new RegionStream.Consumer() {
                @Override public void begin(int slot, int timestamp) {}
                @Override public void data(byte[] data, int offset, int length) {
                    dataArea.write(data, offset, length);
                }
                @Override public void end() {}
            };
            for (var region : index.regions()) {
                for (var chunk : region.chunks) reader.transfer(chunk, sink);
            }
            reader.finish();
        }
        return new LrhdFile(index, dataArea.toByteArray());
    }

    private static boolean sameRegion(ChunkEntry a, ChunkEntry b) {
        return a.rx == b.rx && a.rz == b.rz;
    }
}
