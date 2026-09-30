package com.foldbackup.core.archive;

import com.foldbackup.core.store.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Strict binary codec for complete archive snapshot manifests. */
public final class ArchiveSnapshotCodec {
    public static final int MAGIC = 0x4D43534E;
    public static final byte VERSION = 2;
    private static final int HEADER_SIZE = 50;

    private ArchiveSnapshotCodec() {}

    public static byte[] encode(ArchiveSnapshot snapshot) {
        if (!snapshot.isFull() && !snapshot.isDiff()) throw new IllegalArgumentException("snapshot.type: invalid");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int tagCount = snapshot.tags == null ? 0 : snapshot.tags.size();
        int regionCount = snapshot.regions == null ? 0 : snapshot.regions.size();
        int fileCount = snapshot.files == null ? 0 : snapshot.files.size();
        ByteBuffer head = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(MAGIC).put(VERSION).put((byte) (snapshot.isFull() ? 0 : 1));
        head.putLong(snapshot.epoch).putLong(snapshot.baseEpoch).putLong(snapshot.worldVersion);
        head.putInt(snapshot.regionCount).putInt(snapshot.chunkCount);
        head.putInt(tagCount).putInt(regionCount).putInt(fileCount);
        out.writeBytes(head.array());
        writeString(out, snapshot.sourceSnapshotId);
        writeString(out, snapshot.world);
        writeString(out, snapshot.minecraftVersion);
        if (snapshot.tags != null) for (String tag : snapshot.tags) writeString(out, tag);
        if (snapshot.regions != null) for (ArchiveSnapshot.RegionInfo region : snapshot.regions) {
            writeString(out, region.filename);
            writeLong(out, region.sizeUncompressed);
            writeString(out, region.contentHash);
            writeMetadata(out, region.sourceMetadata);
        }
        if (snapshot.files != null) for (ArchiveSnapshot.FileInfo file : snapshot.files) {
            writeString(out, file.filename);
            writeLong(out, file.sizeUncompressed);
            writeString(out, file.sourceHash);
            writeMetadata(out, file.sourceMetadata);
        }
        return out.toByteArray();
    }

    public static ArchiveSnapshot decode(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) throw new IOException("snapshot.bin truncated header");
        Cursor in = new Cursor(data);
        if (in.i32("magic") != MAGIC) throw new IOException("Invalid snapshot magic");
        int version = in.u8("version");
        if (version != VERSION) throw new IOException("Unsupported snapshot version: " + version);
        int type = in.u8("type");
        if (type > 1) throw new IOException("Invalid snapshot type: " + type);
        ArchiveSnapshot snapshot = new ArchiveSnapshot();
        snapshot.type = type == 0 ? "full" : "diff";
        snapshot.epoch = in.i64("epoch");
        snapshot.baseEpoch = in.i64("baseEpoch");
        snapshot.worldVersion = in.i64("worldVersion");
        snapshot.regionCount = in.count("regionCount");
        snapshot.chunkCount = in.count("chunkCount");
        int tagCount = in.count("tagCount");
        int regionCount = in.count("regionInfoCount");
        int fileCount = in.count("fileInfoCount");
        snapshot.sourceSnapshotId = in.string("sourceSnapshotId");
        snapshot.world = in.string("world");
        snapshot.minecraftVersion = in.string("minecraftVersion");
        snapshot.tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) snapshot.tags.add(in.string("tags[" + i + "]"));
        snapshot.regions = new ArrayList<>(regionCount);
        for (int i = 0; i < regionCount; i++) {
            String field = "regions[" + i + "]";
            String path = in.string(field + ".filename");
            long size = in.i64(field + ".size");
            String hash = in.string(field + ".contentHash");
            SourceMetadata source = in.metadata(field + ".sourceMetadata");
            if (size < 0 || !hash.matches("[0-9a-f]{32}")) throw new IOException(field + ": invalid size/hash");
            TarIndex.validatePath(path);
            snapshot.regions.add(new ArchiveSnapshot.RegionInfo(path, size, hash, source));
        }
        snapshot.files = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            String field = "files[" + i + "]";
            String path = in.string(field + ".filename");
            long size = in.i64(field + ".size");
            String hash = in.string(field + ".sourceHash");
            SourceMetadata source = in.metadata(field + ".sourceMetadata");
            if (size < 0 || !hash.matches("[0-9a-f]{32}")) throw new IOException(field + ": invalid size/hash");
            TarIndex.validatePath(path);
            snapshot.files.add(new ArchiveSnapshot.FileInfo(path, size, hash, source));
        }
        in.finish();
        return snapshot;
    }

    private static void writeMetadata(ByteArrayOutputStream out, SourceMetadata metadata) {
        byte[] bytes = metadata == null ? SourceMetadata.empty().toBytes() : metadata.toBytes();
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes;
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value == null ? "" : value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("snapshot string is invalid UTF-8", e);
        }
        if (bytes.length > 65535) throw new IllegalArgumentException("snapshot string exceeds 65535 bytes");
        out.write(bytes.length & 255);
        out.write((bytes.length >>> 8) & 255);
        out.writeBytes(bytes);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static final class Cursor {
        final ByteBuffer input;
        Cursor(byte[] data) { input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
        int u8(String field) throws IOException {
            if (input.remaining() < 1) throw new IOException(field + ": truncated");
            return input.get() & 255;
        }
        int i32(String field) throws IOException {
            if (input.remaining() < 4) throw new IOException(field + ": truncated");
            return input.getInt();
        }
        int count(String field) throws IOException {
            int value = i32(field);
            if (value < 0 || value > 1_000_000) throw new IOException(field + ": invalid count " + value);
            return value;
        }
        long i64(String field) throws IOException {
            if (input.remaining() < 8) throw new IOException(field + ": truncated");
            return input.getLong();
        }
        String string(String field) throws IOException {
            if (input.remaining() < 2) throw new IOException(field + ": missing length");
            int length = input.getShort() & 65535;
            if (length > input.remaining()) throw new IOException(field + ": truncated");
            ByteBuffer bytes = input.slice();
            bytes.limit(length);
            input.position(input.position() + length);
            try { return StandardCharsets.UTF_8.newDecoder().decode(bytes).toString(); }
            catch (CharacterCodingException e) { throw new IOException(field + ": invalid UTF-8", e); }
        }
        SourceMetadata metadata(String field) throws IOException {
            int length = i32(field + ".length");
            if (length < 2 || length > input.remaining()) throw new IOException(field + ": invalid length");
            byte[] bytes = new byte[length]; input.get(bytes);
            return SourceMetadata.fromBytes(bytes);
        }
        void finish() throws IOException {
            if (input.hasRemaining()) throw new IOException("snapshot.bin has trailing bytes");
        }
    }
}
