package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LrhdRestoreDataTest {
    @TempDir Path temp;
    private int nextArchive;

    private LrhdRestoreData read(byte[] bytes) throws IOException {
        Path archive = temp.resolve("restore-" + nextArchive++ + ".tar");
        try (var channel = FileChannel.open(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                var tar = new TarWriter(channel)) {
            tar.putEntry("delta.lrhd", new ByteArrayInputStream(bytes), bytes.length);
        }
        return LrhdRestoreData.read(TarIndex.read(archive).entries.get("delta.lrhd"));
    }

    private byte[] delta() throws IOException {
        return io.github.truscdo.foldbackup.core.format.LrhdTestSupport.write(3, List.of(
                new io.github.truscdo.foldbackup.core.format.LrhdTestSupport.ChunkEntry(-1, -1, -32, -32, 20, new byte[]{9, 8}),
                new io.github.truscdo.foldbackup.core.format.LrhdTestSupport.ChunkEntry(-1, -1, -31, -32, 21, null),
                new io.github.truscdo.foldbackup.core.format.LrhdTestSupport.ChunkEntry(-1, -1, -1, -1, 22, new byte[]{7})));
    }

    @Test void overlaysChangesDeletionsAndInsertionsAndKeepsOtherCells() throws Exception {
        Region base = new Region(-1, -1, 0);
        for (int i = 0; i < 3; i++) {
            base.setChunk(i, new ChunkData(new byte[]{(byte) i}, -32 + i, -32));
            base.setTimestamp(i, 10 + i);
        }
        byte[] bytes = delta();
        try (var data = read(bytes)) {
            Region result = new Region(-1, -1, 0);
            data.merge(-1, -1, RegionStream.of(base)).read(io.github.truscdo.foldbackup.core.format.LrhTestSupport.objectConsumer(result));
            assertArrayEquals(new byte[]{9, 8}, result.getChunk(0).getRawChunk());
            assertNull(result.getChunk(1));
            assertArrayEquals(new byte[]{2}, result.getChunk(2).getRawChunk());
            assertArrayEquals(new byte[]{7}, result.getChunk(1023).getRawChunk());
            assertEquals(20, result.getTimestamp(0));
            assertEquals(21, result.getTimestamp(1));
            assertEquals(12, result.getTimestamp(2));
            assertEquals(22, result.getTimestamp(1023));
        }
    }

    @Test void stagesOnlyReplacementPayloadsThenMergesIndependently() throws Exception {
        Region base = new Region(-1, -1, 0);
        for (int i = 0; i < 3; i++) {
            base.setChunk(i, new ChunkData(new byte[]{(byte) i}, -32 + i, -32));
            base.setTimestamp(i, 10 + i);
        }
        byte[] bytes = delta();
        Path payload = temp.resolve("region.bin");
        try (var data = read(bytes)) {
            try (var staged = data.stageNext(payload, true)) {
                assertEquals(3, Files.size(staged.payload()));
                data.finish();
                Region result = new Region(-1, -1, 0);
                data.mergeStaged(-1, -1, RegionStream.of(base), staged)
                        .read(io.github.truscdo.foldbackup.core.format.LrhTestSupport.objectConsumer(result));
                assertArrayEquals(new byte[]{9, 8}, result.getChunk(0).getRawChunk());
                assertNull(result.getChunk(1));
                assertArrayEquals(new byte[]{2}, result.getChunk(2).getRawChunk());
                assertArrayEquals(new byte[]{7}, result.getChunk(1023).getRawChunk());
            }
            assertFalse(Files.exists(payload), "Region task should release its staged payload");
        }
    }

    @Test void stagedPayloadIsRecheckedBeforeMerge() throws Exception {
        byte[] bytes = delta();
        Path payload = temp.resolve("corrupt-region.bin");
        try (var data = read(bytes);
                var staged = data.stageNext(payload, true)) {
            data.finish();
            byte[] altered = Files.readAllBytes(payload);
            altered[0] ^= 1;
            Files.write(payload, altered);
            assertThrows(IOException.class, () -> data.mergeStaged(-1, -1,
                    consumer -> { for (int i = 0; i < 1024; i++) { consumer.begin(i, 0); consumer.end(); } },
                    staged).read(new RegionStream.Accumulator()));
        }
    }

    @Test void rejectsCorruptMetadataHashFooterTruncationAndInvalidLengths() throws Exception {
        byte[] bytes = delta();
        byte[] badMetadataHash = bytes.clone();
        badMetadataHash[LrhdFormat.METADATA_HASH_OFFSET] ^= 1;
        assertThrows(IOException.class, () -> read(badMetadataHash));

        byte[] badFooter = bytes.clone(); badFooter[badFooter.length - 2] ^= 1;
        assertThrows(IOException.class, () -> {
            try (var data = read(badFooter)) { data.finish(); }
        });

        assertThrows(IOException.class, () -> read(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] badLength = bytes.clone(); ByteBuffer.wrap(badLength).putLong(24, Long.MAX_VALUE);
        assertThrows(IOException.class, () -> read(badLength));
    }

    @Test void tombstoneOnlyDeltaNeedsNoCompressedFrame() throws Exception {
        byte[] bytes = io.github.truscdo.foldbackup.core.format.LrhdTestSupport.write(3,
                List.of(new io.github.truscdo.foldbackup.core.format.LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 17, null)));
        try (var data = read(bytes)) {
            var summary = RegionStream.inspect(data.merge(0, 0, consumer -> {
                consumer.begin(0, 1); consumer.data(new byte[]{1}, 0, 1); consumer.end();
            }));
            assertEquals(0, summary.nbtBytes());
            assertEquals(17, summary.timestamps()[0]);
        }
    }
}
