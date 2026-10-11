package io.github.truscdo.foldbackup.core.format;

import io.github.truscdo.foldbackup.core.HashUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Path encoded = Files.createTempFile("lrhd-fixture-", ".encoded");
        Path index = encoded.resolveSibling(encoded.getFileName() + ".index");
        try {
            try (var output = Files.newOutputStream(encoded);
                    var writer = new LrhdEncodingWriter(output, index, level, 1)) {
                for (int regionIndex = 0; regionIndex < plans.size(); regionIndex++) {
                    for (ChunkEntry chunk : groups.get(regionIndex))
                        if (chunk.nbt != null) writer.replacement(List.of(chunk.nbt));
                    writer.region(plans.get(regionIndex));
                }
                writer.finish();
            }
            try (var input = io.github.truscdo.foldbackup.core.archive.LrhdLayoutSource
                    .open(encoded, 0, Files.size(encoded))) { return input.readAllBytes(); }
        } finally {
            Files.deleteIfExists(index);
            Files.deleteIfExists(encoded);
        }
    }

    /** Decodes raw tar-entry bytes in either the standard or encoding layout. */
    public static LrhdFile readEntry(byte[] entryBytes) throws IOException {
        Path temp = Files.createTempFile("lrhd-entry-", ".lrhd");
        try {
            Files.write(temp, entryBytes);
            try (var input = io.github.truscdo.foldbackup.core.archive.LrhdLayoutSource
                    .open(temp, 0, entryBytes.length)) {
                return read(input.readAllBytes());
            }
        } finally {
            Files.deleteIfExists(temp);
        }
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
