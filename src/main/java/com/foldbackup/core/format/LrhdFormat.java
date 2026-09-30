package com.foldbackup.core.format;

import com.github.luben.zstd.ZstdInputStream;
import com.foldbackup.core.HashUtil;
import com.foldbackup.core.StreamingHash;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** LRHD v1: dynamic uncompressed index followed by one NBT Zstd frame. */
public final class LrhdFormat {
    /** ASCII "LRHDLRHD". The same eight bytes are used as the file footer. */
    public static final long SIGNATURE = 0x4c5248444c524844L;
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 52;
    public static final int METADATA_HASH_OFFSET = 36;
    public static final int METADATA_HASH_SIZE = 16;
    public static final int FOOTER_SIZE = 8;
    public static final int REGION_INDEX_SIZE = 12;
    public static final int CHUNK_INDEX_RECORD_SIZE = 28;
    public static final int DEFAULT_LEVEL = 19;
    public static final int DEFAULT_WINDOW_LOG = 27;

    private LrhdFormat() {}

    public record Header(int compressionLevel, int regionCount, int changedChunkCount,
                         long dataLength, long compressedDataLength, byte[] metadataHash) {
        public Header {
            metadataHash = metadataHash == null ? new byte[METADATA_HASH_SIZE] : metadataHash.clone();
            if (metadataHash.length != METADATA_HASH_SIZE)
                throw new IllegalArgumentException("LRHD metadata hash must be 16 bytes");
        }

        @Override public byte[] metadataHash() { return metadataHash.clone(); }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Header h)) return false;
            return compressionLevel == h.compressionLevel && regionCount == h.regionCount
                    && changedChunkCount == h.changedChunkCount && dataLength == h.dataLength
                    && compressedDataLength == h.compressedDataLength
                    && Arrays.equals(metadataHash, h.metadataHash);
        }

        @Override public int hashCode() {
            int result = Objects.hash(compressionLevel, regionCount, changedChunkCount,
                    dataLength, compressedDataLength);
            return 31 * result + Arrays.hashCode(metadataHash);
        }
    }

    public record Index(Header header, List<RegionIndex> regions, long dataOffset) {}

    public static final class RegionIndex {
        public int rx, rz;
        public List<ChunkIndex> chunks = new ArrayList<>();
    }

    /** Offsets are derived in memory from the order of replacement records. */
    public static final class ChunkIndex {
        public int cx, cz;
        public long ts, offset;
        public int length;
        public String hash = "";
        public boolean deleted;

        public int slotIndex() { return (cz & 31) * 32 + (cx & 31); }
    }

    public static int compareRegions(int rx, int rz, int otherX, int otherZ) {
        int x = Integer.compare(rx, otherX);
        return x == 0 ? Integer.compare(rz, otherZ) : x;
    }

    static void validateRegionAfter(int previousX, int previousZ, int rx, int rz) throws IOException {
        if (compareRegions(previousX, previousZ, rx, rz) >= 0)
            throw new IOException("LRHD regions must be strictly sorted");
    }

    static void validateCoordinates(int rx, int rz) throws IOException {
        if (rx < (Integer.MIN_VALUE >> 5) || rx > (Integer.MAX_VALUE >> 5)
                || rz < (Integer.MIN_VALUE >> 5) || rz > (Integer.MAX_VALUE >> 5))
            throw new IOException("LRHD region coordinates overflow chunk coordinates");
    }

    static void validateTimestamp(long timestamp) throws IOException {
        if (timestamp < Integer.MIN_VALUE || timestamp > Integer.MAX_VALUE)
            throw new IOException("LRHD timestamp exceeds supported MCA range");
    }

    static int chunkX(int rx, int slot) throws IOException {
        validateCoordinates(rx, 0);
        try {
            return Math.addExact(Math.multiplyExact(rx, 32), slot % 32);
        } catch (ArithmeticException e) {
            throw new IOException("LRHD chunk X coordinate overflow", e);
        }
    }

    static int chunkZ(int rz, int slot) throws IOException {
        validateCoordinates(0, rz);
        try {
            return Math.addExact(Math.multiplyExact(rz, 32), slot / 32);
        } catch (ArithmeticException e) {
            throw new IOException("LRHD chunk Z coordinate overflow", e);
        }
    }

    public static String decodeUtf8(byte[] bytes, String field) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("Invalid LRHD UTF-8: " + field, e);
        }
    }

    public static Header validateHeader(byte[] bytes, long fileSize) throws IOException {
        if (bytes.length < HEADER_SIZE || fileSize < HEADER_SIZE + FOOTER_SIZE)
            throw new IOException("Truncated LRHD header");
        ByteBuffer h = ByteBuffer.wrap(bytes);
        if (h.getLong() != SIGNATURE || (h.get() & 255) != VERSION || h.get() != 0)
            throw new IOException("Invalid LRHD signature/version/reserved");
        int level = h.getShort();
        int regionCount = h.getInt();
        int changedChunkCount = h.getInt();
        long dataLength = h.getLong();
        long compressedDataLength = h.getLong();
        byte[] metadataHash = new byte[METADATA_HASH_SIZE]; h.get(metadataHash);
        if (level < Short.MIN_VALUE || level > Short.MAX_VALUE
                || regionCount < 0 || changedChunkCount < 0 || dataLength < 0 || compressedDataLength < 0
                || (regionCount == 0) != (changedChunkCount == 0)
                || regionCount > changedChunkCount
                || compressedDataLength > fileSize - HEADER_SIZE - FOOTER_SIZE)
            throw new IOException("Invalid LRHD header counts/lengths");
        // Every region has at least one 12-byte header. This bounds the trusted
        // region count before the variable index is parsed.
        long minimumIndex = HEADER_SIZE + (long) regionCount * REGION_INDEX_SIZE + FOOTER_SIZE;
        if (minimumIndex > fileSize)
            throw new IOException("LRHD region count exceeds file boundary");
        return new Header(level, regionCount, changedChunkCount, dataLength,
                compressedDataLength, metadataHash);
    }

    /** Validates all metadata before returning. Leaves borrowed input at the NBT frame. */
    public static Index readIndex(InputStream input, long size) throws IOException {
        if (size < 0) throw new IOException("Invalid LRHD file size");
        DataInputStream in = new DataInputStream(input);
        byte[] headerBytes = new byte[HEADER_SIZE];
        in.readFully(headerBytes);
        Header h = validateHeader(headerBytes, size);

        byte[] normalizedHeader = headerBytes.clone();
        Arrays.fill(normalizedHeader, METADATA_HASH_OFFSET, HEADER_SIZE, (byte) 0);
        var metadata = new StreamingHash.Hash128();
        metadata.update(normalizedHeader, 0, normalizedHeader.length);
        InputStream hashingInput = new FilterInputStream(input) {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }

            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                int n = in.read(bytes, off, len);
                if (n > 0) metadata.update(bytes, off, n);
                return n;
            }
        };
        DataInputStream idx = new DataInputStream(hashingInput);

        List<RegionIndex> regions = new ArrayList<>();
        long dataOffset = HEADER_SIZE;
        long dataPosition = 0;
        long changed = 0;
        RegionIndex previous = null;
        for (int r = 0; r < h.regionCount; r++) {
            StreamIo.checkCancelled();
            if (dataOffset > size - FOOTER_SIZE - REGION_INDEX_SIZE)
                throw new EOFException("Truncated LRHD region index");
            byte[] regionBytes = new byte[REGION_INDEX_SIZE];
            idx.readFully(regionBytes);
            dataOffset = checkedAdd(dataOffset, REGION_INDEX_SIZE, "LRHD index length");
            ByteBuffer regionBuffer = ByteBuffer.wrap(regionBytes);
            var region = new RegionIndex();
            region.rx = regionBuffer.getInt();
            region.rz = regionBuffer.getInt();
            int chunkCount = Short.toUnsignedInt(regionBuffer.getShort());
            if (regionBuffer.getShort() != 0 || chunkCount == 0 || chunkCount > 1024)
                throw new IOException("Invalid LRHD region group");
            validateCoordinates(region.rx, region.rz);
            if (previous != null) validateRegionAfter(previous.rx, previous.rz, region.rx, region.rz);
            long recordBytes = (long) chunkCount * CHUNK_INDEX_RECORD_SIZE;
            if (recordBytes > size - FOOTER_SIZE - dataOffset)
                throw new EOFException("Truncated LRHD chunk index");
            int previousSlot = -1;
            for (int i = 0; i < chunkCount; i++) {
                byte[] record = new byte[CHUNK_INDEX_RECORD_SIZE];
                idx.readFully(record);
                dataOffset = checkedAdd(dataOffset, CHUNK_INDEX_RECORD_SIZE, "LRHD index length");
                ByteBuffer b = ByteBuffer.wrap(record);
                int slot = Short.toUnsignedInt(b.getShort());
                int state = b.get() & 255;
                if (slot <= previousSlot || slot >= 1024 || (state != 1 && state != 2) || b.get() != 0)
                    throw new IOException("Invalid LRHD chunk record ordering/state");
                previousSlot = slot;
                int length = b.getInt();
                int timestamp = b.getInt();
                byte[] chunkHash = new byte[16]; b.get(chunkHash);
                if (state == 2) {
                    if (length != 0 || !allZero(chunkHash))
                        throw new IOException("Invalid LRHD deletion record");
                } else if (length <= 0) {
                    throw new IOException("Invalid LRHD replacement length");
                }
                int cx = chunkX(region.rx, slot), cz = chunkZ(region.rz, slot);
                var chunk = new ChunkIndex();
                chunk.cx = cx; chunk.cz = cz; chunk.ts = timestamp; chunk.length = length;
                chunk.deleted = state == 2;
                chunk.offset = chunk.deleted ? 0 : dataPosition;
                chunk.hash = chunk.deleted ? "" : HashUtil.bytesToHex(chunkHash);
                if (length > h.dataLength - dataPosition)
                    throw new IOException("LRHD lengths exceed data area");
                dataPosition = checkedAdd(dataPosition, length, "LRHD data length");
                changed++;
                region.chunks.add(chunk);
            }
            regions.add(region);
            previous = region;
        }
        long expectedCompressed = size - dataOffset - FOOTER_SIZE;
        if (expectedCompressed < 0 || h.compressedDataLength != expectedCompressed
                || changed != h.changedChunkCount || dataPosition != h.dataLength)
            throw new IOException("LRHD index/header length mismatch");
        if (!Arrays.equals(metadata.digest(), h.metadataHash()))
            throw new IOException("LRHD metadata hash mismatch");
        return new Index(h, List.copyOf(regions), dataOffset);
    }

    private static long checkedAdd(long left, long right, String what) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IOException(what + " overflow", e);
        }
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) if (b != 0) return false;
        return true;
    }

    /** Sequential verified payload cursor. The caller owns the underlying input. */
    public static final class DataReader implements AutoCloseable {
        private final InputStream owner;
        private final InputStream data, compressed;
        private final Index index;
        private final byte[] buffer = new byte[65536];
        private long position;
        private boolean finished;

        public DataReader(InputStream input, Index index) throws IOException {
            owner = input; this.index = index;
            compressed = StreamIo.limited(input, index.header.compressedDataLength());
            if (index.header.compressedDataLength() == 0) data = InputStream.nullInputStream();
            else data = new ZstdInputStream(new LrhdSingleFrameInput(compressed));
        }

        @FunctionalInterface
        private interface PayloadSink { void write(byte[] bytes, int off, int len) throws IOException; }

        public void transfer(ChunkIndex chunk, RegionStream.Consumer sink) throws IOException {
            transferPayload(chunk, sink == null ? null : sink::data);
        }

        /** Copies a verified replacement payload to a bounded staging stream. */
        public void transferTo(ChunkIndex chunk, OutputStream sink) throws IOException {
            transferPayload(chunk, sink == null ? null : (bytes, off, len) ->
                    com.foldbackup.core.format.IoBudget.call(() -> { sink.write(bytes, off, len); return null; }));
        }

        /** Consumes and verifies a replacement payload without retaining it. */
        public void skip(ChunkIndex chunk) throws IOException {
            transferPayload(chunk, null);
        }

        private void transferPayload(ChunkIndex chunk, PayloadSink sink) throws IOException {
            if (finished) throw new IOException("LRHD data reader already finished");
            if (chunk.deleted) return;
            if (chunk.offset != position) throw new IOException("LRHD chunks consumed out of order");
            var hash = new StreamingHash.Hash128();
            long remaining = chunk.length;
            while (remaining > 0) {
                StreamIo.checkCancelled();
                int n = data.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (n <= 0) throw new EOFException("Truncated LRHD chunk");
                hash.update(buffer, 0, n);
                if (sink != null) sink.write(buffer, 0, n);
                remaining -= n; position += n;
            }
            if (!HashUtil.bytesToHex(hash.digest()).equals(chunk.hash))
                throw new IOException("LRHD chunk hash mismatch: " + chunk.cx + "," + chunk.cz);
        }

        public void finish() throws IOException {
            if (finished) return;
            if (position != index.header.dataLength() || data.read() != -1 || compressed.read() != -1)
                throw new IOException("LRHD data length/frame mismatch");
            DataInputStream in = new DataInputStream(owner);
            if (in.readLong() != SIGNATURE || owner.read() != -1)
                throw new IOException("Invalid LRHD footer or trailing bytes");
            finished = true;
        }

        public void close() throws IOException { data.close(); }
    }

    public static Index verify(InputStream input, long size) throws IOException {
        Index index = readIndex(input, size);
        try (var data = new DataReader(input, index)) {
            for (var r : index.regions) for (var c : r.chunks) data.skip(c);
            data.finish();
        }
        return index;
    }

    static long indexLength(List<RegionIndex> regions) throws IOException {
        long length = 0;
        int previousRx = 0, previousRz = 0;
        boolean havePrevious = false;
        for (var region : regions) {
            if (region == null) throw new IOException("Null LRHD region plan");
            validateRegionPlan(region, previousRx, previousRz, havePrevious);
            long records = Math.multiplyExact(region.chunks.size(), CHUNK_INDEX_RECORD_SIZE);
            length = Math.addExact(length, Math.addExact(REGION_INDEX_SIZE, records));
            previousRx = region.rx; previousRz = region.rz; havePrevious = true;
        }
        return length;
    }

    private static void validateRegionPlan(RegionIndex region, int previousRx, int previousRz,
            boolean havePrevious) throws IOException {
        validateCoordinates(region.rx, region.rz);
        if (havePrevious) validateRegionAfter(previousRx, previousRz, region.rx, region.rz);
        if (region.chunks == null || region.chunks.isEmpty() || region.chunks.size() > 1024)
            throw new IOException("Invalid LRHD region plan");
    }

    static int changedChunkCount(List<RegionIndex> regions) throws IOException {
        long count = 0;
        for (var region : regions) count = Math.addExact(count, region.chunks.size());
        if (count > Integer.MAX_VALUE) throw new IOException("Too many LRHD changed chunks");
        return (int) count;
    }

    static void writeIndex(SeekableByteChannel output, List<RegionIndex> regions,
            StreamingHash.Hash128 hash) throws IOException {
        OutputStream raw = new BufferedOutputStream(StreamIo.output(output), 32 * 1024);
        OutputStream sink = hash == null ? raw : new FilterOutputStream(raw) {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException {
                hash.update(bytes, off, len); out.write(bytes, off, len);
            }
        };
        try (DataOutputStream out = new DataOutputStream(sink)) {
            int previousRx = 0, previousRz = 0; boolean havePrevious = false;
            for (var region : regions) {
                validateRegionPlan(region, previousRx, previousRz, havePrevious);
                out.writeInt(region.rx); out.writeInt(region.rz); out.writeShort(region.chunks.size()); out.writeShort(0);
                int previousSlot = -1;
                for (var chunk : region.chunks) {
                    int slot = chunk.slotIndex();
                    if (slot <= previousSlot || slot >= 1024)
                        throw new IOException("LRHD chunk plan is not strictly sorted");
                    if ((chunk.cx >> 5) != region.rx || (chunk.cz >> 5) != region.rz)
                        throw new IOException("LRHD chunk plan coordinate mismatch");
                    validateTimestamp(chunk.ts);
                    if (chunk.deleted) {
                        if (chunk.length != 0 || (chunk.hash != null && !chunk.hash.isEmpty()))
                            throw new IOException("Invalid LRHD deletion plan");
                    } else {
                        if (chunk.length <= 0 || chunk.hash == null || chunk.hash.length() != 32)
                            throw new IOException("Invalid LRHD replacement plan");
                    }
                    out.writeShort(slot); out.writeByte(chunk.deleted ? 2 : 1); out.writeByte(0);
                    out.writeInt(chunk.length); out.writeInt((int) chunk.ts);
                    out.write(chunk.deleted ? new byte[16] : HashUtil.hexToBytes(chunk.hash));
                    previousSlot = slot;
                }
                previousRx = region.rx; previousRz = region.rz; havePrevious = true;
            }
        }
    }

}
