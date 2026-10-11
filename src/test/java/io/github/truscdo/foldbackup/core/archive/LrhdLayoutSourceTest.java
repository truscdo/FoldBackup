package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.format.LrhdEncodingWriter;
import io.github.truscdo.foldbackup.core.format.LrhdFormat;
import io.github.truscdo.foldbackup.core.format.LrhdTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LrhdLayoutSourceTest {
    @TempDir Path temp;

    private static InputStream open(Path path) throws IOException {
        return LrhdLayoutSource.open(path, 0, Files.size(path));
    }

    private Path encode(byte[] nbt, boolean deletion) throws Exception {
        Path encoded = Files.createTempFile(temp, "encoded", ".lrhd");
        try (var output = Files.newOutputStream(encoded);
                var writer = new LrhdEncodingWriter(output, Path.of(encoded + ".index"), 1, 2)) {
            var region = new LrhdFormat.RegionIndex(); region.rx = -2; region.rz = 3;
            if (nbt != null) {
                var chunk = new LrhdFormat.ChunkIndex(); chunk.cx = -64; chunk.cz = 96;
                chunk.ts = 123; chunk.length = nbt.length;
                chunk.hash = HashUtil.bytesToHex(HashUtil.xxHash128(nbt));
                region.chunks.add(chunk);
                writer.replacement(List.of(nbt));
            }
            if (deletion) {
                var chunk = new LrhdFormat.ChunkIndex(); chunk.cx = -63; chunk.cz = 96;
                chunk.deleted = true; chunk.ts = -1; region.chunks.add(chunk);
            }
            writer.region(region);
            writer.finish();
        }
        return encoded;
    }

    @Test void encodingLayoutIsReorderedToStandardOrderWithoutChangingMetadataOrPayload() throws Exception {
        byte[] payload = new byte[5 << 20]; new Random(17).nextBytes(payload);
        Path path = encode(payload, true);
        byte[] raw = Files.readAllBytes(path), decoded;
        try (var input = open(path)) { decoded = input.readAllBytes(); }
        assertEquals(raw.length, decoded.length);
        assertEquals(LrhdFormat.SIGNATURE, ByteBuffer.wrap(decoded).getLong());
        assertEquals(LrhdFormat.SIGNATURE, ByteBuffer.wrap(raw).getLong(raw.length - 8));
        assertArrayEquals(Arrays.copyOfRange(raw, raw.length - 52, raw.length - 8),
                Arrays.copyOfRange(decoded, 8, 52));
        var result = LrhdTestSupport.read(decoded); // Rejects extra frames and verifies hashes/checksum.
        assertEquals(2, result.allChunks().size());
        assertArrayEquals(payload, result.chunkNbt(result.allChunks().getFirst()));
        assertTrue(result.allChunks().getLast().deleted);
    }

    @Test void deletionOnlyAndEmptyEncodingsRemainReadable() throws Exception {
        for (boolean deletion : new boolean[] {false, true}) {
            Path path = encode(null, deletion);
            try (var input = open(path)) {
                byte[] data = input.readAllBytes();
                assertEquals(0, ByteBuffer.wrap(data).getLong(28));
                assertEquals(deletion ? 1 : 0, LrhdTestSupport.read(data).allChunks().size());
            }
        }
    }

    @Test void standardLayoutPassesThroughUnchanged() throws Exception {
        byte[] standard = LrhdTestSupport.write(1, List.of(
                new LrhdTestSupport.ChunkEntry(-2, 3, -64, 96, 123, new byte[] {1, 2, 3}),
                new LrhdTestSupport.ChunkEntry(-2, 3, -63, 96, -1, null)));
        Path path = temp.resolve("standard.lrhd"); Files.write(path, standard);
        try (var input = open(path)) { assertArrayEquals(standard, input.readAllBytes()); }
    }

    @Test void rejectsDamagedSignatureMetadataIndexLengthsAndFooter() throws Exception {
        byte[] original = Files.readAllBytes(encode(new byte[] {1, 2, 3}, true));
        int metadata = original.length - 52;
        int index = 8 + (int) ByteBuffer.wrap(original).getLong(metadata + 20);
        for (int offset : new int[] {0, metadata, metadata + 4, metadata + 20, metadata + 28,
                index, index + 12 + 8, original.length - 1}) {
            byte[] corrupt = original.clone(); corrupt[offset] ^= 1;
            Path path = temp.resolve("corrupt.lrhd"); Files.write(path, corrupt);
            assertThrows(IOException.class, () -> {
                try (var ignored = open(path)) { fail("Accepted corrupt metadata"); }
            }, "offset " + offset);
        }
        Path truncated = temp.resolve("short.lrhd");
        Files.write(truncated, Arrays.copyOf(original, 40));
        assertThrows(IOException.class, () -> LrhdLayoutSource.open(truncated, 0, 40));
    }
}
