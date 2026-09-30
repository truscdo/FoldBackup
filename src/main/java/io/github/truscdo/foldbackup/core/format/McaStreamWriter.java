package io.github.truscdo.foldbackup.core.format;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.zip.*;

/** Writes one chunk at a time; oversized compressed chunks are moved to MCC without buffering NBT. */
public final class McaStreamWriter {
    private McaStreamWriter() {}
    public static void write(Path destination, int rx, int rz, long mtime, int level, RegionStream.Source source)
            throws IOException {
        Path parent = destination.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, destination.getFileName() + ".", ".wip");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                StreamIo.write(file, new byte[8192]);
                ByteBuffer header = ByteBuffer.allocate(8192);
                class Writer implements RegionStream.Consumer, AutoCloseable {
                    int index = -1; long start; Deflater deflater; DeflaterOutputStream compressed;
                    boolean active;
                    public void begin(int i, int timestamp) throws IOException {
                        if (active || i <= index || i >= 1024) throw new IOException("Invalid MCA event order");
                        index = i; active = true; start = file.position(); header.putInt(4096 + i * 4, timestamp);
                    }
                    public void data(byte[] bytes, int off, int len) throws IOException {
                        if (!active) throw new IOException("Missing MCA chunk begin");
                        if (len == 0) return;
                        if (compressed == null) {
                            StreamIo.write(file, new byte[5]);
                            deflater = new Deflater(level);
                            compressed = new DeflaterOutputStream(StreamIo.output(file), deflater, 65536);
                        }
                        compressed.write(bytes, off, len);
                    }
                    public void end() throws IOException {
                        if (!active) throw new IOException("Missing MCA chunk begin");
                        active = false;
                        if (compressed == null) return;
                        compressed.finish(); deflater.end(); deflater = null; compressed = null;
                        long end = file.position(), bytes = end - start;
                        long sectors = (bytes + 4095) / 4096;
                        if (start / 4096 > 0xffffffL) throw new IOException("MCA sector offset overflow");
                        if (sectors > 255) {
                            Path mcc = parent.resolve(AnvilFormat.externalChunkFileName(rx, rz, index));
                            Path wip = Files.createTempFile(parent, mcc.getFileName() + ".", ".wip");
                            try {
                                file.position(start + 5);
                                try (var output = Files.newOutputStream(wip)) {
                                    var input = StreamIo.limited(Channels.newInputStream(file), bytes - 5);
                                    byte[] buffer = new byte[65536];
                                    for (int n; (n = input.read(buffer)) != -1;) {
                                        int length = n;
                                        IoBudget.call(() -> { output.write(buffer, 0, length); return null; });
                                    }
                                }
                                Files.setLastModifiedTime(wip, FileTime.fromMillis(mtime));
                                Files.move(wip, mcc, StandardCopyOption.REPLACE_EXISTING);
                            } finally { Files.deleteIfExists(wip); }
                            file.truncate(start); file.position(start);
                            ByteBuffer placeholder = ByteBuffer.allocate(4096);
                            placeholder.putInt(1).put((byte) AnvilFormat.EXTERNAL_FILE_COMPRESSION_TYPE);
                            StreamIo.write(file, placeholder.array()); sectors = 1;
                        } else {
                            file.position(start);
                            StreamIo.write(file, ByteBuffer.allocate(5).putInt((int) bytes - 4)
                                    .put((byte) AnvilFormat.COMPRESSION_TYPE_ZLIB).array());
                            file.position(end); StreamIo.write(file, new byte[(int) (sectors * 4096 - bytes)]);
                        }
                        header.putInt(index * 4, (int) ((start / 4096) << 8) | (int) sectors);
                    }
                    public void close() { if (deflater != null) deflater.end(); }
                }
                try (var writer = new Writer()) {
                    source.read(writer);
                    if (writer.active) throw new IOException("Unfinished MCA chunk");
                }
                file.position(0); StreamIo.write(file, header.array());
                IoBudget.call(() -> { file.force(true); return null; });
            }
            Files.setLastModifiedTime(temp, FileTime.fromMillis(mtime));
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
