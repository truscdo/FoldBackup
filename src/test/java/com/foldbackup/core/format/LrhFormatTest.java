package com.foldbackup.core.format;

import com.github.luben.zstd.ZstdCompressCtx;
import com.foldbackup.core.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LrhFormat} 单元测试：v3 round-trip、payload 布局（无置换表）与 contentHash。
 */
class LrhFormatTest {

    @TempDir
    Path tempDir;

    // ── helpers ──────────────────────────────────────────────

    private static byte[] chunkBytes(int seed, int size) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** 稀疏 + 密集混合的样例 region，覆盖大量空槽位与边界槽位。 */
    private static Region sampleRegion(int regionX, int regionZ) {
        Region r = new Region(regionX, regionZ, 1_700_000_000L);
        int[] indices = { 0, 1, 5, 31, 32, 33, 100, 511, 512, 1023 };
        for (int i = 0; i < indices.length; i++) {
            int idx = indices[i];
            r.setChunk(idx, new ChunkData(chunkBytes(idx, 64 + i * 17), idx % 32, idx / 32));
            r.setTimestamp(idx, 1_700_000_000 + idx);
        }
        return r;
    }

    private static void assertRegionEquals(Region expected, Region actual) {
        assertEquals(expected.getRegionX(), actual.getRegionX());
        assertEquals(expected.getRegionZ(), actual.getRegionZ());
        assertEquals(expected.chunkCount(), actual.chunkCount());
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            assertEquals(expected.getTimestamp(i), actual.getTimestamp(i), "ts@" + i);
            ChunkData a = expected.getChunk(i);
            ChunkData b = actual.getChunk(i);
            if (a == null) {
                assertNull(b, "chunk@" + i + " should be empty");
            } else {
                assertNotNull(b, "chunk@" + i + " should be present");
                assertArrayEquals(a.getRawChunk(), b.getRawChunk(), "chunk data @" + i);
            }
        }
    }

    private Path writeToTempFile(Region region) throws IOException {
        Path file = tempDir.resolve("r." + region.getRegionX() + "." + region.getRegionZ() + ".lrh");
        LrhTestSupport.writeRegionLinear(file, region, 3);
        return file;
    }

    /** 解压 payload → 修改 → 重压 → 重组（保留原 preheader 的 content_hash）。 */
    private byte[] tamperPayload(byte[] lrhData, java.util.function.IntUnaryOperator sizeMutator,
            java.util.function.BiConsumer<byte[], Integer> chunkMutator) throws IOException {
        byte[] payload = decompressPayload(lrhData);
        chunkMutator.accept(payload, 0);
        byte[] compressed = compressWithChecksum(payload, 3);
        byte[] result = Arrays.copyOf(lrhData,
                LrhFormat.COMPRESSED_DATA_OFFSET + compressed.length + LrhFormat.FOOTER_SIZE);
        System.arraycopy(compressed, 0, result, LrhFormat.COMPRESSED_DATA_OFFSET, compressed.length);
        ByteBuffer.wrap(result).putInt(23, compressed.length).putLong(result.length - 8, LrhFormat.LINEAR_SIGNATURE);
        return result;
    }

    // ── v3 round-trip ────────────────────────────────────────

    @Test
    void roundTrip_writeThenRead_matches() throws IOException {
        Region original = sampleRegion(3, -5);
        Path file = writeToTempFile(original);

        assertEquals(LrhFormat.LINEAR_VERSION, Files.readAllBytes(file)[8],
                "preheader version must be " + LrhFormat.LINEAR_VERSION);
        assertTrue(LrhTestSupport.verifyLinear(file), "verifyLinear must accept v3");

        Region parsed = LrhTestSupport.openRegionLinear(file);
        assertRegionEquals(original, parsed);
    }

    @Test
    void roundTrip_emptyRegion() throws IOException {
        Region original = new Region(0, 0, 123L);
        Path file = writeToTempFile(original);
        Region parsed = LrhTestSupport.openRegionLinear(file);
        assertRegionEquals(original, parsed);
    }

    @Test
    void roundTrip_fullRegion() throws IOException {
        Region original = new Region(-1, 1, 999L);
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            original.setChunk(i, new ChunkData(chunkBytes(i, 32 + (i % 7) * 3), i % 32, i / 32));
            original.setTimestamp(i, 1_000 + i);
        }
        Path file = writeToTempFile(original);
        Region parsed = LrhTestSupport.openRegionLinear(file);
        assertRegionEquals(original, parsed);
    }

    @Test
    void payload_hasNoPermutationTable() {
        Region region = sampleRegion(0, 0);
        byte[] payload = LrhTestSupport.buildUncompressedPayload(region);
        int expected = 0;
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            if (region.getChunk(i) != null)
                expected += region.getChunk(i).getRawChunk().length;
        }
        assertEquals(expected, payload.length,
                "payload = natural-order NBT only");
    }

    // ── contentHash ──────────────────────────────────────────

    @Test
    void contentHash_storedInPreheader() throws IOException {
        Region region = sampleRegion(0, 0);
        Path file = writeToTempFile(region);
        byte[] expected = HashUtil.xxHash128(LrhTestSupport.buildContentPayload(region));
        assertArrayEquals(expected, LrhTestSupport.readContentHashFromLrh(file));
    }

    @Test
    void contentPayload_hasSizeOnlyHeaderTable() {
        Region region = sampleRegion(0, 0);
        byte[] payload = LrhTestSupport.buildContentPayload(region);
        int expected = LrhFormat.CONTENT_HEADER_TABLE_SIZE;
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            if (region.getChunk(i) != null)
                expected += region.getChunk(i).getRawChunk().length;
        }
        assertEquals(expected, payload.length,
                "content payload = size-only header table (no timestamps) + chunk data");
    }

    @Test
    void contentHash_ignoresTimestamps() {
        Region a = sampleRegion(0, 0);
        Region b = sampleRegion(0, 0);
        // Same chunk content, different timestamps (simulates a re-write / restart)
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            b.setTimestamp(i, b.getTimestamp(i) + 1_000_000);
        }
        assertArrayEquals(LrhTestSupport.buildContentPayload(a), LrhTestSupport.buildContentPayload(b),
                "content payload must not include timestamps");
        assertArrayEquals(HashUtil.xxHash128(LrhTestSupport.buildContentPayload(a)),
                HashUtil.xxHash128(LrhTestSupport.buildContentPayload(b)),
                "contentHash must be stable across timestamp-only changes");
    }

    @Test
    void contentHash_differsWhenChunkContentDiffers() {
        Region a = sampleRegion(0, 0);
        Region b = sampleRegion(0, 0);
        // Same timestamps, different chunk content
        b.setChunk(5, new ChunkData(chunkBytes(999, 128), 5 % 32, 5 / 32));
        assertFalse(java.util.Arrays.equals(
                HashUtil.xxHash128(LrhTestSupport.buildContentPayload(a)),
                HashUtil.xxHash128(LrhTestSupport.buildContentPayload(b))),
                "contentHash must change when chunk content changes");
    }

    // ── contentHash 读取侧校验 ───────────────────────────────

    @Test
    void contentHash_verifyRejectsTamperedChunk() throws IOException {
        Region region = sampleRegion(0, 0);
        Path file = writeToTempFile(region);
        byte[] lrh = Files.readAllBytes(file);
        // 篡改第一个非空 chunk（grid 序中 chunk 0，offset = HEADER_TABLE_SIZE），同长度替换
        byte[] replacement = chunkBytes(777, region.getChunk(0).getRawChunk().length);
        byte[] tampered = tamperPayload(lrh, i -> i, (data, off) -> {
            if (off == 0) {
                System.arraycopy(replacement, 0, data, off, replacement.length);
            }
        });
        assertThrows(IOException.class,
                () -> LrhTestSupport.openRegionLinear(tampered, 0, 0, 1_700_000_000L),
                "tampered chunk data must fail content hash verification");
    }

    @Test
    void contentHash_verifyAcceptsTimestampChanges() throws IOException {
        Region region = sampleRegion(0, 0);
        Path file = writeToTempFile(region);
        byte[] lrh = Files.readAllBytes(file);
        byte[] out = lrh.clone();
        ByteBuffer table = ByteBuffer.wrap(out);
        for (int i = 0; i < 1024; i++) {
            int pos = LrhFormat.TABLE_OFFSET + i * 8 + 4;
            table.putInt(pos, table.getInt(pos) + 1_000_000);
        }
        Region parsed = LrhTestSupport.openRegionLinear(out, 0, 0, 1_700_000_000L);
        assertNotNull(parsed, "timestamp-only changes must pass content hash verification");
    }

    @Test
    void contentHash_rejectsZeroHash() throws IOException {
        Region region = sampleRegion(0, 0);
        Path file = writeToTempFile(region);
        byte[] lrh = Files.readAllBytes(file);
        // 当前格式的内容哈希不可省略。
        for (int i = LrhFormat.CONTENT_HASH_OFFSET; i < LrhFormat.CONTENT_HASH_OFFSET
                + LrhFormat.CONTENT_HASH_SIZE; i++) {
            lrh[i] = 0;
        }
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(lrh, 0, 0, 1_700_000_000L));
    }

    private byte[] decompressPayload(byte[] lrhData) throws IOException {
        int compressedStart = LrhFormat.COMPRESSED_DATA_OFFSET;
        int compressedEnd = lrhData.length - LrhFormat.FOOTER_SIZE;
        byte[] compressed = Arrays.copyOfRange(lrhData, compressedStart, compressedEnd);
        try (var input = new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return input.readAllBytes();
        }
    }

    private byte[] compressWithChecksum(byte[] payload, int level) throws IOException {
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            return ctx.setLevel(level).setChecksum(true).compress(payload);
        }
    }

    @Test
    void checksumCorruptionRejected() throws IOException {
        byte[] lrh = Files.readAllBytes(writeToTempFile(sampleRegion(0, 0)));
        lrh[LrhFormat.COMPRESSED_DATA_OFFSET] ^= 1;
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(lrh, 0, 0, 0),
                "zstd frame checksum must reject corrupted compressed data");
    }

    // ── 透明重压缩（方案 C+D）────────────────────────────────

    @Test
    void recompress_preservesContentAndHash() throws IOException {
        Region original = sampleRegion(2, 4);
        Path file = writeToTempFile(original);
        byte[] lrh = Files.readAllBytes(file);
        byte[] originalHash = LrhTestSupport.readContentHashFromBytes(lrh);

        byte[] recompressed = LrhTestSupport.recompress(lrh, 19);

        // contentHash 逐位不变（payload 未变）
        assertArrayEquals(originalHash, LrhTestSupport.readContentHashFromBytes(recompressed),
                "contentHash must be preserved verbatim");
        // preheader 版本不变
        assertEquals(LrhFormat.LINEAR_VERSION, recompressed[8]);
        // compression_level 更新为 19（字节 17-19，BE）
        assertEquals(19, ByteBuffer.wrap(recompressed, 17, 2).getShort());
        // complete_region_length 更新为新压缩帧长度（字节 23-27，BE）
        int newLen = ByteBuffer.wrap(recompressed, 23, 4).getInt();
        assertEquals(recompressed.length - LrhFormat.COMPRESSED_DATA_OFFSET - LrhFormat.FOOTER_SIZE, newLen);
        // footer 签名保留
        assertTrue(LrhTestSupport.verifyLinear(recompressed));

        // 解析后与原始 region 完全一致
        Region parsed = LrhTestSupport.openRegionLinear(recompressed, 2, 4, 1_700_000_000L);
        assertRegionEquals(original, parsed);
    }

    @Test
    void recompress_rejectsInvalidInput() {
        assertThrows(IOException.class, () -> LrhTestSupport.recompress(new byte[10], 19),
                "too-short input must be rejected");
        byte[] bad = new byte[LrhFormat.MIN_FILE_SIZE];
        assertThrows(IOException.class, () -> LrhTestSupport.recompress(bad, 19),
                "bad signature must be rejected");
    }
}
