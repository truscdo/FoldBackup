package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        ArchiveBinaryCodec.writeString(out, snapshot.sourceSnapshotId, "snapshot");
        ArchiveBinaryCodec.writeString(out, snapshot.world, "snapshot");
        ArchiveBinaryCodec.writeString(out, snapshot.minecraftVersion, "snapshot");
        if (snapshot.tags != null) for (String tag : snapshot.tags) ArchiveBinaryCodec.writeString(out, tag, "snapshot");
        if (snapshot.regions != null) for (ArchiveSnapshot.RegionInfo region : snapshot.regions) {
            ArchiveBinaryCodec.writeString(out, region.filename, "snapshot");
            ArchiveBinaryCodec.writeLong(out, region.sizeUncompressed);
            ArchiveBinaryCodec.writeString(out, region.contentHash, "snapshot");
            ArchiveBinaryCodec.writeMetadata(out, region.sourceMetadata);
        }
        if (snapshot.files != null) for (ArchiveSnapshot.FileInfo file : snapshot.files) {
            ArchiveBinaryCodec.writeString(out, file.filename, "snapshot");
            ArchiveBinaryCodec.writeLong(out, file.sizeUncompressed);
            ArchiveBinaryCodec.writeString(out, file.sourceHash, "snapshot");
            ArchiveBinaryCodec.writeMetadata(out, file.sourceMetadata);
        }
        return out.toByteArray();
    }

    public static ArchiveSnapshot decode(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) throw new IOException("snapshot.bin truncated header");
        ArchiveBinaryCodec.Cursor in = new ArchiveBinaryCodec.Cursor(data, "snapshot.bin");
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
}
