package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.StreamIo;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Header-only scan of an uncompressed tar; entry streams own independent cursors. */
final class TarIndex {
    record Entry(Path archive, String name, long offset, long size) {
        InputStream open() throws IOException {
            FileChannel channel = FileChannel.open(archive);
            try {
                channel.position(offset);
                InputStream bounded = StreamIo.limited(java.nio.channels.Channels.newInputStream(channel), size);
                return new FilterInputStream(bounded) {
                    @Override public void close() throws IOException { channel.close(); }
                };
            } catch (Throwable failure) {
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        byte[] bytes() throws IOException {
            if (size > Integer.MAX_VALUE) throw new IOException("Metadata too large: " + name);
            try (InputStream in = open()) { return in.readNBytes((int) size); }
        }
    }

    final Map<String, Entry> entries = new LinkedHashMap<>();

    static TarIndex read(Path archive) throws IOException {
        TarIndex index = new TarIndex();
        try (FileChannel channel = FileChannel.open(archive)) {
            long length = channel.size();
            // TarReader remains the single source of header/checksum/prefix rules.
            InputStream input = new InputStream() {
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                public int read(byte[] b, int off, int len) throws IOException {
                    StreamIo.checkCancelled();
                    return channel.read(ByteBuffer.wrap(b, off, len));
                }
                public long skip(long n) throws IOException {
                    StreamIo.checkCancelled();
                    long position = channel.position();
                    if (n < 0 || n > length - position) throw new EOFException("Truncated tar entry/padding");
                    channel.position(position + n);
                    return n;
                }
            };
            try (TarReader reader = new TarReader(input)) {
                TarReader.Entry entry;
                while ((entry = reader.nextEntry()) != null) {
                    long offset = channel.position();
                    if (entry.size < 0 || entry.size > length - offset)
                        throw new EOFException("Invalid tar entry bounds: " + entry.name);
                    if (!entry.isFile) continue;
                    validatePath(entry.name);
                    if (index.entries.putIfAbsent(entry.name,
                            new Entry(archive, entry.name, offset, entry.size)) != null)
                        throw new IOException("Duplicate tar path: " + entry.name);
                }
            }
        }
        return index;
    }

    static void validatePath(String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.startsWith("/"))
            throw new IOException("Invalid archive path: " + name);
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("Invalid archive path: " + name);
    }

    ArchiveSnapshot snapshot(String type) throws IOException {
        Entry entry = entries.get("snapshot.bin");
        ArchiveSnapshot meta = entry == null ? null : ArchiveSnapshotCodec.decode(entry.bytes());
        if (meta == null || !type.equals(meta.type)) throw new IOException("Missing/invalid " + type + " snapshot.bin");
        return meta;
    }
}
