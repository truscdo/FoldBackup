package io.github.truscdo.foldbackup.core.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

/** Object and array adapters retained only for tests that exercise LRH fixtures. */
public final class LrhTestSupport {
    private LrhTestSupport() {}

    public static Region openRegionLinear(Path path) throws IOException {
        int[] coords = AnvilFormat.parseRegionCoords(path.getFileName().toString());
        Region region = new Region(coords[0], coords[1], Files.getLastModifiedTime(path).toMillis());
        LrhFormat.read(path, objectConsumer(region));
        return region;
    }

    public static Region openRegionLinear(byte[] data, int x, int z, long mtime) throws IOException {
        Region region = new Region(x, z, mtime);
        LrhFormat.read(new ByteArrayInputStream(data), data.length, objectConsumer(region));
        return region;
    }

    public static RegionStream.Consumer objectConsumer(Region region) {
        return new RegionStream.Consumer() {
            int index;
            ByteArrayOutputStream bytes;

            @Override public void begin(int i, int timestamp) {
                index = i;
                region.setTimestamp(i, timestamp);
                bytes = new ByteArrayOutputStream();
            }

            @Override public void data(byte[] buffer, int offset, int length) {
                bytes.write(buffer, offset, length);
            }

            @Override public void end() {
                if (bytes.size() != 0) {
                    region.setChunk(index, new ChunkData(bytes.toByteArray(),
                            region.getRegionX() * 32 + index % 32, region.getRegionZ() * 32 + index / 32));
                }
            }
        };
    }

    public static void writeRegionLinear(Path destination, Region region, int level) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, destination.getFileName() + ".", ".wip");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                LrhFormat.write(channel, RegionStream.of(region), level);
                channel.force(true);
            }
            Files.setLastModifiedTime(temp, FileTime.fromMillis(region.getMtimeMillis()));
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static byte[] writeRegionLinearBytes(Region region, int level) throws IOException {
        var output = new TestMemoryChannel();
        LrhFormat.write(output, RegionStream.of(region), level);
        return output.toByteArray();
    }

    public static byte[] recompress(byte[] data, int level) throws IOException {
        var output = new TestMemoryChannel();
        LrhFormat.recompress(new ByteArrayInputStream(data), data.length, output, level);
        return output.toByteArray();
    }

    public static byte[] buildContentPayload(Region region) {
        var output = new ByteArrayOutputStream();
        ByteBuffer lengths = ByteBuffer.allocate(LrhFormat.CONTENT_HEADER_TABLE_SIZE);
        for (int i = 0; i < LrhFormat.HEADER_CELLS; i++) {
            ChunkData chunk = region.getChunk(i);
            lengths.putInt(chunk == null ? 0 : chunk.getRawChunk().length);
            if (chunk != null) output.writeBytes(chunk.getRawChunk());
        }
        output.writeBytes(lengths.array());
        return output.toByteArray();
    }

    public static byte[] buildUncompressedPayload(Region region) {
        var output = new ByteArrayOutputStream();
        for (int i = 0; i < LrhFormat.HEADER_CELLS; i++) {
            ChunkData chunk = region.getChunk(i);
            if (chunk != null) output.writeBytes(chunk.getRawChunk());
        }
        return output.toByteArray();
    }

    public static byte[] readContentHashFromLrh(Path path) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            byte[] header = new byte[LrhFormat.PREHEADER_SIZE];
            input.readFully(header);
            return readContentHashFromBytes(header);
        }
    }

    public static byte[] readContentHashFromBytes(byte[] bytes) throws IOException {
        if (bytes.length < LrhFormat.PREHEADER_SIZE) throw new IOException("Truncated LRH header");
        return Arrays.copyOfRange(bytes, LrhFormat.CONTENT_HASH_OFFSET,
                LrhFormat.CONTENT_HASH_OFFSET + LrhFormat.CONTENT_HASH_SIZE);
    }

    public static boolean verifyLinear(Path path) {
        try (var channel = FileChannel.open(path)) {
            if (channel.size() < LrhFormat.MIN_FILE_SIZE) return false;
            byte[] header = StreamIo.read(channel, LrhFormat.PREHEADER_SIZE);
            channel.position(channel.size() - LrhFormat.FOOTER_SIZE);
            long footer = ByteBuffer.wrap(StreamIo.read(channel, LrhFormat.FOOTER_SIZE)).getLong();
            return validEnvelope(header, channel.size(), footer);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean verifyLinear(byte[] data) {
        return data.length >= LrhFormat.MIN_FILE_SIZE && validEnvelope(data, data.length,
                ByteBuffer.wrap(data, data.length - LrhFormat.FOOTER_SIZE, LrhFormat.FOOTER_SIZE).getLong());
    }

    private static boolean validEnvelope(byte[] header, long size, long footer) {
        return ByteBuffer.wrap(header).getLong() == LrhFormat.LINEAR_SIGNATURE
                && header[8] == LrhFormat.LINEAR_VERSION
                && footer == LrhFormat.LINEAR_SIGNATURE
                && Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(23))
                        == size - LrhFormat.COMPRESSED_DATA_OFFSET - LrhFormat.FOOTER_SIZE;
    }
}
