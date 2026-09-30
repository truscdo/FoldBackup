package io.github.truscdo.foldbackup.core.format;

import io.github.truscdo.foldbackup.core.HashUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LrhdFormatTest {

    private static byte[] nbt(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTrip_basic() throws Exception {
        List<LrhdTestSupport.ChunkEntry> chunks = new ArrayList<>();
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1000, nbt("chunk-a")));
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 1, 0, 1001, nbt("chunk-b-longer")));
        chunks.add(new LrhdTestSupport.ChunkEntry(1, -2, 33, -63, 1002, nbt("chunk-c")));

        byte[] data = LrhdTestSupport.write(LrhdFormat.DEFAULT_LEVEL, chunks);

        LrhdTestSupport.LrhdFile f = LrhdTestSupport.read(data);
        assertEquals(LrhdFormat.DEFAULT_LEVEL, f.compressionLevel);
        assertEquals(3, f.allChunks().size());

        LrhdFormat.ChunkIndex c0 = f.findChunk(0, 0, 0, 0);
        assertNotNull(c0);
        assertEquals(1000, c0.ts);
        assertFalse(c0.deleted);
        assertArrayEquals(nbt("chunk-a"), f.chunkNbt(c0));

        LrhdFormat.ChunkIndex c1 = f.findChunk(0, 0, 1, 0);
        assertArrayEquals(nbt("chunk-b-longer"), f.chunkNbt(c1));

        LrhdFormat.ChunkIndex c2 = f.findChunk(1, -2, 33, -63);
        assertArrayEquals(nbt("chunk-c"), f.chunkNbt(c2));
    }

    @Test
    void roundTrip_deletedTombstone() throws Exception {
        List<LrhdTestSupport.ChunkEntry> chunks = new ArrayList<>();
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 5, 5, 2000, nbt("alive")));
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 6, 5, 2001, null)); // deleted

        byte[] data = LrhdTestSupport.write(19, chunks);
        LrhdTestSupport.LrhdFile f = LrhdTestSupport.read(data);

        LrhdFormat.ChunkIndex del = f.findChunk(0, 0, 6, 5);
        assertNotNull(del);
        assertTrue(del.deleted);
        assertNull(f.chunkNbt(del));
        assertEquals("", del.hash);

        LrhdFormat.ChunkIndex alive = f.findChunk(0, 0, 5, 5);
        assertFalse(alive.deleted);
        assertArrayEquals(nbt("alive"), f.chunkNbt(alive));
    }

    @Test
    void hashMatchesXxh3_128OfNbt() throws Exception {
        byte[] chunkNbt = nbt("hash-me");
        List<LrhdTestSupport.ChunkEntry> chunks = new ArrayList<>();
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, chunkNbt));

        byte[] data = LrhdTestSupport.write(19, chunks);
        LrhdTestSupport.LrhdFile f = LrhdTestSupport.read(data);

        LrhdFormat.ChunkIndex c = f.allChunks().get(0);
        assertEquals(HashUtil.bytesToHex(HashUtil.xxHash128(chunkNbt)), c.hash);
    }

    @Test
    void emptyDataArea() throws Exception {
        byte[] data = LrhdTestSupport.write(19, new ArrayList<>());
        LrhdTestSupport.LrhdFile f = LrhdTestSupport.read(data);
        assertEquals(0, f.allChunks().size());
        assertEquals(0, f.regions.size());
    }

    @Test
    void invalidMagicRejected() {
        byte[] data = new byte[LrhdFormat.HEADER_SIZE];
        assertThrows(IOException.class, () -> LrhdTestSupport.read(data));
    }

    @Test
    void truncatedFileRejected() {
        assertThrows(IOException.class, () -> LrhdTestSupport.read(new byte[10]));
    }

    @Test
    void checksumCorruptionRejected() throws Exception {
        List<LrhdTestSupport.ChunkEntry> chunks = new ArrayList<>();
        chunks.add(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, nbt("checksum")));
        byte[] data = LrhdTestSupport.write(19, chunks);
        data[data.length - 1] ^= 1;
        assertThrows(IOException.class, () -> LrhdTestSupport.read(data),
                "zstd frame checksum must reject corrupted compressed data");
    }

    @Test
    void strictLayoutValidationRejectsHeaderCountMismatch() throws Exception {
        byte[] data = LrhdTestSupport.write(19,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, nbt("x"))));
        ByteBuffer.wrap(data).putInt(16, 0); // header changedChunkCount
        assertThrows(IOException.class, () -> LrhdTestSupport.read(data));
    }

    @Test
    void strictLayoutValidationRejectsTrailingIndexBytes() throws Exception {
        byte[] original = LrhdTestSupport.write(19,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, nbt("x"))));
        byte[] data = Arrays.copyOf(original, original.length + 1);
        assertThrows(IOException.class, () -> LrhdTestSupport.read(data));
    }

    @Test
    void strictIndexValidationRejectsDuplicateChunk() throws Exception {
        assertThrows(IOException.class, () -> LrhdTestSupport.write(19, List.of(
                new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, nbt("a")),
                new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 2, nbt("b")))));
    }

    @Test
    void strictPayloadValidationRejectsIndexHashMismatch() throws Exception {
        byte[] data = LrhdTestSupport.write(19,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 1, nbt("x"))));
        int hashOffset = 52 + 12 + 12;
        data[hashOffset] ^= 1;
        rehashMetadata(data); // A valid metadata hash cannot conceal mismatched NBT.
        assertThrows(IOException.class, () -> LrhdTestSupport.read(data));
    }

    @Test
    void arrayWriterRejectsInvalidCompressionLevel() {
        assertThrows(IOException.class, () -> LrhdTestSupport.write(Short.MAX_VALUE + 1, List.of()));
    }

    private static void rehashMetadata(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int end = 52;
        int regions = b.getInt(12);
        for (int i = 0; i < regions; i++) {
            int chunks = Short.toUnsignedInt(b.getShort(end + 8));
            end += 12 + chunks * 28;
        }
        byte[] header = Arrays.copyOfRange(bytes, 0, 52);
        Arrays.fill(header, 36, 52, (byte) 0);
        var hash = new io.github.truscdo.foldbackup.core.StreamingHash.Hash128();
        hash.update(header, 0, header.length);
        hash.update(bytes, 52, end - 52);
        System.arraycopy(hash.digest(), 0, bytes, 36, 16);
    }

    @Test void dynamicLayoutAndCanonicalOrdering() throws Exception {
        var a = new LrhdTestSupport.ChunkEntry(10, 0, 320, 0, 8, nbt("last"));
        var b = new LrhdTestSupport.ChunkEntry(-1, -1, -1, -1, 9, nbt("first"));
        byte[] bytes = LrhdTestSupport.write(3, List.of(a, b));
        assertArrayEquals(bytes, LrhdTestSupport.write(3, List.of(b, a)));
        var header = ByteBuffer.wrap(bytes);
        assertEquals(1, bytes[8]); assertEquals(52, LrhdFormat.HEADER_SIZE);
        assertEquals(2, header.getInt(12)); assertEquals(2, header.getInt(16));
        int dataOffset = 52 + 2 * (12 + 28);
        assertEquals(bytes.length, dataOffset + header.getLong(28) + 8);
        assertEquals(9, header.getLong(20));
        assertEquals(-1, LrhdTestSupport.read(bytes).regions.getFirst().rx);
        assertEquals(0, LrhdTestSupport.read(bytes).allChunks().getFirst().offset);
    }

    @Test void metadataHashProtectsCoordinatesTimestampAndDeletionTarget() throws Exception {
        byte[] original = LrhdTestSupport.write(3,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 10, null)));
        int region = 52, slot = region + 12;
        for (int offset : new int[]{0, region + 3, slot + 15, slot + 8}) {
            byte[] bad = original.clone(); bad[offset] ^= 1;
            assertThrows(IOException.class, () -> LrhdTestSupport.read(bad), "offset=" + offset);
        }
        byte[] badCount = original.clone();
        ByteBuffer.wrap(badCount).putShort(region + 8, (short) 0);
        rehashMetadata(badCount);
        assertThrows(IOException.class, () -> LrhdTestSupport.read(badCount));
    }

    @Test void rejectsInvalidSlotsEvenWithRecomputedMetadataHash() throws Exception {
        byte[] original = LrhdTestSupport.write(3,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 10, nbt("x"))));
        int slot = 52 + 12;
        for (int variant = 0; variant < 6; variant++) {
            byte[] bad = original.clone(); ByteBuffer b = ByteBuffer.wrap(bad);
            switch (variant) {
                case 0 -> bad[slot + 2] = 3;
                case 1 -> bad[slot + 3] = 1;
                case 2 -> b.putInt(slot + 4, -1);
                case 3 -> b.putShort(slot, (short) 1024);
                case 4 -> b.putShort(52 + 8, (short) 0);
                case 5 -> b.putInt(16, 2);
            }
            rehashMetadata(bad);
            assertThrows(IOException.class, () -> LrhdTestSupport.read(bad), "variant=" + variant);
        }
    }

    @Test void rejectsOldVersionAndUnrepresentableTimestamp() throws Exception {
        byte[] old = LrhdTestSupport.write(3, List.of()); old[8] = 3;
        assertThrows(IOException.class, () -> LrhdTestSupport.read(old));
        assertThrows(IOException.class, () -> LrhdTestSupport.write(3,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, Long.MAX_VALUE, null))));
    }

    @Test void rejectsAdditionalFrameEvenWhenItsLengthsAndMetadataAreValid() throws Exception {
        byte[] original = LrhdTestSupport.write(3,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 10, nbt("x"))));
        var out = new java.io.ByteArrayOutputStream();
        try (var zstd = new com.github.luben.zstd.ZstdOutputStream(out, 3)) { zstd.setChecksum(true); }
        byte[] frame = out.toByteArray();
        byte[] bad = Arrays.copyOf(original, original.length + frame.length);
        System.arraycopy(frame, 0, bad, original.length, frame.length);
        ByteBuffer b = ByteBuffer.wrap(bad); b.putLong(28, b.getLong(28) + frame.length);
        rehashMetadata(bad);
        assertThrows(IOException.class, () -> LrhdTestSupport.read(bad));
    }

    @Test void rejectsDataFrameWithoutChecksum() throws Exception {
        byte[] original = LrhdTestSupport.write(3,
                List.of(new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 10, nbt("x"))));
        int start = 52 + 12 + 28;
        byte[] frame = com.github.luben.zstd.Zstd.compress(nbt("x"), 3);
        byte[] bad = Arrays.copyOf(original, start + frame.length + 8);
        System.arraycopy(frame, 0, bad, start, frame.length);
        ByteBuffer.wrap(bad).putLong(28, frame.length)
                .putLong(bad.length - 8, LrhdFormat.SIGNATURE);
        rehashMetadata(bad);
        assertThrows(IOException.class, () -> LrhdTestSupport.read(bad));
    }
}
