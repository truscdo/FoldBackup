package com.foldbackup.core.format;

import com.foldbackup.core.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamingRegionTest {
    @TempDir Path temp;
    @Test void separatedCompressionFinishesFrameAndBackfillsAtNonzeroOffset() throws Exception {
        byte[] payload = new byte[200000];
        new java.util.Random(42).nextBytes(payload);
        Region region = new Region(0, 0, 0);
        region.setChunk(3, new ChunkData(payload, 3, 0));
        region.setTimestamp(3, 123456);
        region.setTimestamp(1000, 87654); // Empty-cell timestamps must also survive.
        byte[] expected = LrhTestSupport.writeRegionLinearBytes(region, 3);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var frame = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
        };
        var summary = LrhFormat.compress(frame, RegionStream.of(region), 3);
        assertFalse(closed.get());
        try (var decoder = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(frame.toByteArray()))) {
            assertArrayEquals(payload, decoder.readAllBytes());
        }
        Path file = temp.resolve("embedded.lrh");
        byte[] prefix = {9, 8, 7};
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            StreamIo.write(channel, prefix);
            StreamIo.write(channel, new byte[LrhFormat.COMPRESSED_DATA_OFFSET]);
            StreamIo.write(channel, frame.toByteArray());
            LrhFormat.finish(channel, prefix.length, summary, 3);
            assertEquals(prefix.length + expected.length, channel.position());
            assertTrue(channel.isOpen());
        }
        byte[] actual = Files.readAllBytes(file);
        assertArrayEquals(prefix, java.util.Arrays.copyOf(actual, prefix.length));
        assertArrayEquals(expected, java.util.Arrays.copyOfRange(actual, prefix.length, actual.length));
    }
    @Test void unknownLengthSingleUseSourceAndStreamingRoundTrip() throws Exception {
        Path path = temp.resolve("r.0.0.lrh"); AtomicInteger calls = new AtomicInteger();
        RegionStream.Source source = consumer -> {
            assertEquals(1, calls.incrementAndGet());
            byte[] buffer = new byte[8192]; java.util.Arrays.fill(buffer, (byte) 42);
            consumer.begin(0, 123);
            for (int i = 0; i < 1024; i++) consumer.data(buffer, 0, buffer.length);
            consumer.end(); consumer.begin(1023, 456); consumer.end();
        };
        RegionStream.Summary summary;
        try (var out = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            summary = LrhFormat.write(out, source, 3); assertTrue(out.isOpen());
        }
        var actual = LrhFormat.read(path, new RegionStream.Accumulator());
        assertTrue(summary.same(actual)); assertEquals(8L << 20, actual.nbtBytes());
        Path mca = temp.resolve("r.0.0.mca");
        McaStreamWriter.write(mca, 0, 0, 0, 6, c -> LrhFormat.read(path, c));
        assertTrue(summary.same(RegionStream.inspect(McaStream.source(mca))));
        Path other = temp.resolve("other.lrh");
        try (var out = FileChannel.open(other, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertTrue(summary.same(LrhFormat.recompress(path, out, 6)));
        }
    }
    @Test void malformedTablesAndExtraNbtAreRejected() throws Exception {
        Region region = new Region(0,0,0); region.setChunk(0,new ChunkData(new byte[]{1,2,3},0,0));
        byte[] data = LrhTestSupport.writeRegionLinearBytes(region,3);
        byte[] negative = data.clone(); ByteBuffer.wrap(negative).putInt(LrhFormat.TABLE_OFFSET,-1);
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(negative,0,0,0));
        byte[] shortLength = data.clone(); ByteBuffer.wrap(shortLength).putInt(LrhFormat.TABLE_OFFSET,2);
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(shortLength,0,0,0));
        byte[] longLength = data.clone(); ByteBuffer.wrap(longLength).putInt(LrhFormat.TABLE_OFFSET,4);
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(longLength,0,0,0));
        assertThrows(IOException.class, () -> LrhTestSupport.openRegionLinear(java.util.Arrays.copyOf(data,500),0,0,0));
    }
    @Test void regionContentIdentityIsIndependentOfAnvilEncoding() throws Exception {
        Region region = new Region(0,0,0); byte[] bytes = new byte[2 << 20]; new java.util.Random(1).nextBytes(bytes);
        region.setChunk(0,new ChunkData(bytes,0,0)); region.setTimestamp(0,42);
        Path mca=temp.resolve("r.0.0.mca"); AnvilTestSupport.writeRegionAnvil(mca,region,6);
        assertTrue(RegionStream.inspect(RegionStream.of(region)).same(RegionStream.inspect(McaStream.source(mca))));
        byte[] raw=Files.readAllBytes(mca);
        String contentHash=HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(region)).hash());
        assertEquals(32,contentHash.length());
        Path streamed=temp.resolve("streamed/r.0.0.mca");
        McaStreamWriter.write(streamed,0,0,0,6,RegionStream.of(region));
        assertArrayEquals(raw,Files.readAllBytes(streamed));
        assertArrayEquals(Files.readAllBytes(temp.resolve("c.0.0.mcc")),Files.readAllBytes(streamed.getParent().resolve("c.0.0.mcc")));
        assertEquals(contentHash,HashUtil.bytesToHex(RegionStream.inspect(McaStream.source(streamed)).hash()));
        assertEquals(32,McaHasher.hashFile(mca).length());
    }
    @Test void mcaStreamReportsStoredEmptyChunkSeparatelyFromEmptyCell() throws Exception {
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[0], 0, 0));
        Path mca = temp.resolve("r.0.0.mca");
        AnvilTestSupport.writeRegionAnvil(mca, region, 6);
        boolean[] present = new boolean[2];
        McaStream.read(mca, new RegionStream.Consumer() {
            int index;
            public void begin(int value, int timestamp) { index = value; }
            public void present(boolean value) { if (index < present.length) present[index] = value; }
            public void data(byte[] bytes, int offset, int length) {}
            public void end() {}
        });
        assertTrue(present[0]);
        assertFalse(present[1]);
        assertNotNull(AnvilTestSupport.readRegionAnvil(mca).getChunk(0));
        assertNull(AnvilTestSupport.readRegionAnvil(mca).getChunk(1));
    }
}
