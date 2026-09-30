package com.foldbackup.core.format;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Buffered Anvil fixture writer retained for tests and byte-for-byte comparisons. */
public final class AnvilTestSupport {
    private AnvilTestSupport() {}

    /** Reads a test MCA fixture through the production streaming decoder. */
    public static Region readRegionAnvil(Path source) throws IOException {
        int[] coords = AnvilFormat.parseRegionCoords(source.getFileName().toString());
        Region region = new Region(coords[0], coords[1], Files.getLastModifiedTime(source).toMillis());
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        int[] active = {-1};
        boolean[] present = {false};
        McaStream.read(source, new RegionStream.Consumer() {
            @Override public void begin(int index, int timestamp) {
                active[0] = index;
                chunk.reset();
                present[0] = false;
                region.setTimestamp(index, timestamp);
            }

            @Override public void present(boolean value) { present[0] = value; }

            @Override public void data(byte[] buffer, int offset, int length) {
                chunk.write(buffer, offset, length);
            }

            @Override public void end() {
                if (!present[0]) return;
                int index = active[0];
                int x = coords[0] * Region.REGION_DIMENSION + index % Region.REGION_DIMENSION;
                int z = coords[1] * Region.REGION_DIMENSION + index / Region.REGION_DIMENSION;
                region.setChunk(index, new ChunkData(chunk.toByteArray(), x, z));
            }
        });
        return region;
    }

    public static void writeRegionAnvil(Path destination, Region region, int compression) throws IOException {
        Path destDir = destination.getParent();
        if (destDir != null) Files.createDirectories(destDir);

        int[] startSectors = new int[Region.HEADER_CELLS];
        int freeSector = 2;
        byte[][] sectorData = new byte[Region.HEADER_CELLS][];

        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            startSectors[i] = freeSector;
            ChunkData chunk = region.getChunk(i);
            if (chunk != null) {
                byte[] compressed = compressZlib(chunk.getRawChunk(), compression);
                ByteBuffer record = ByteBuffer.allocate(5 + compressed.length).order(ByteOrder.BIG_ENDIAN);
                record.putInt(compressed.length + 1).put((byte) AnvilFormat.COMPRESSION_TYPE_ZLIB).put(compressed);

                int padding = (AnvilFormat.ANVIL_SECTOR - record.position() % AnvilFormat.ANVIL_SECTOR)
                        % AnvilFormat.ANVIL_SECTOR;
                byte[] padded = new byte[record.position() + padding];
                System.arraycopy(record.array(), 0, padded, 0, record.position());
                int sectorCount = padded.length / AnvilFormat.ANVIL_SECTOR;

                if (sectorCount > 255) {
                    int cx = region.getRegionX() * Region.REGION_DIMENSION + i % Region.REGION_DIMENSION;
                    int cz = region.getRegionZ() * Region.REGION_DIMENSION + i / Region.REGION_DIMENSION;
                    Path mccPath = destDir.resolve("c." + cx + "." + cz + ".mcc");
                    Path mccWip = destDir.resolve("c." + cx + "." + cz + ".mcc.wip");
                    Files.write(mccWip, compressed);
                    Files.setLastModifiedTime(mccWip, FileTime.fromMillis(region.getMtimeMillis()));
                    Files.move(mccWip, mccPath, StandardCopyOption.REPLACE_EXISTING);

                    ByteBuffer placeholder = ByteBuffer.allocate(AnvilFormat.ANVIL_SECTOR).order(ByteOrder.BIG_ENDIAN);
                    placeholder.putInt(1).put((byte) AnvilFormat.EXTERNAL_FILE_COMPRESSION_TYPE);
                    sectorData[i] = placeholder.array();
                    freeSector++;
                } else {
                    sectorData[i] = padded;
                    freeSector += sectorCount;
                }
            } else {
                sectorData[i] = new byte[0];
            }
        }

        byte[] locationTable = new byte[Region.HEADER_CELLS * 4];
        ByteBuffer locations = ByteBuffer.wrap(locationTable).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            if (region.getChunk(i) != null && sectorData[i].length > 0) {
                int sectorCount = sectorData[i].length / AnvilFormat.ANVIL_SECTOR;
                locations.putInt((startSectors[i] << 8) | (sectorCount & 0xFF));
            } else {
                locations.putInt(0);
            }
        }

        byte[] timestampTable = new byte[Region.HEADER_CELLS * 4];
        ByteBuffer timestamps = ByteBuffer.wrap(timestampTable).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < Region.HEADER_CELLS; i++) timestamps.putInt(region.getTimestamp(i));

        try (var output = new ByteArrayOutputStream()) {
            output.write(locationTable);
            output.write(timestampTable);
            for (byte[] sectors : sectorData) output.write(sectors);

            Path wip = destination.resolveSibling(destination.getFileName() + ".wip");
            Files.write(wip, output.toByteArray());
            Files.setLastModifiedTime(wip, FileTime.fromMillis(region.getMtimeMillis()));
            Files.move(wip, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] compressZlib(byte[] data, int level) throws IOException {
        Deflater deflater = new Deflater(level);
        try (var output = new ByteArrayOutputStream();
                var compressed = new DeflaterOutputStream(output, deflater)) {
            compressed.write(data);
            compressed.finish();
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
