package io.github.truscdo.foldbackup.core.format;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.InflaterInputStream;

/** Streaming equivalent of the original Anvil reader, including truncated-sector handling. */
public final class McaStream {
    private McaStream() {}
    public static RegionStream.Source source(Path path) { return consumer -> read(path, consumer); }
    public static void read(Path path, RegionStream.Consumer consumer) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) return;
            if (size < 8192) throw new IOException("Truncated Anvil header: " + path);
            ByteBuffer header = ByteBuffer.wrap(StreamIo.read(channel, 8192));
            int[] coords = AnvilFormat.parseRegionCoords(path.getFileName().toString());
            byte[] buffer = new byte[65536];
            for (int index = 0; index < 1024; index++) {
                StreamIo.checkCancelled();
                consumer.begin(index, header.getInt(4096 + index * 4));
                int location = header.getInt(index * 4);
                int start = location >>> 8, sectors = location & 255;
                consumer.present(start > 0 && sectors > 0);
                long position = start * 4096L;
                long available = Math.min(size, (start + (long) sectors) * 4096) - position;
                if (start > 0 && sectors > 0 && available >= 5) {
                    channel.position(position);
                    ByteBuffer record = ByteBuffer.wrap(StreamIo.read(channel, 5));
                    int recordLength = record.getInt();
                    int type = record.get() & 255;
                    if (recordLength < 1) throw new IOException("Invalid Anvil record length at " + index);
                    long length = Math.min(recordLength - 1L, available - 5);
                    InputStream compressed;
                    if (type == AnvilFormat.COMPRESSION_TYPE_ZLIB) {
                        compressed = StreamIo.limited(Channels.newInputStream(channel), length);
                    } else if (type == AnvilFormat.EXTERNAL_FILE_COMPRESSION_TYPE) {
                        String fileName = AnvilFormat.externalChunkFileName(coords[0], coords[1], index);
                        compressed = Files.newInputStream(path.getParent().resolve(fileName));
                    } else {
                        throw new IOException("Unimplemented compression type " + type + " at chunk " + index);
                    }
                    try (InputStream input = new InflaterInputStream(compressed)) {
                        for (int n; (n = input.read(buffer)) != -1;) {
                            StreamIo.checkCancelled();
                            consumer.data(buffer, 0, n);
                        }
                    }
                }
                consumer.end();
            }
        }
    }
}
