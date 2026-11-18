package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Strict binary codec for file-path updates in a archive differential archive. */
public final class FilesManifestCodec {
    public static final int MAGIC = 0x4D43464D;
    public static final byte VERSION = 2;
    private FilesManifestCodec() {}

    public static byte[] encode(DiffComputer.FilesManifest manifest) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var files = manifest.files == null ? java.util.List.<DiffComputer.FilesManifest.FileDiff>of() : manifest.files;
        out.writeBytes(ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).put(VERSION).putLong(manifest.baseEpoch).putInt(files.size()).array());
        for (var file : files) {
            ArchiveBinaryCodec.writeString(out, file.path, "files.bin");
            out.write(file.deleted ? 1 : 0);
            if (!file.deleted) {
                ArchiveBinaryCodec.writeString(out, file.sourceHash, "files.bin");
                ArchiveBinaryCodec.writeLong(out, file.sizeUncompressed);
                ArchiveBinaryCodec.writeMetadata(out, file.sourceMetadata);
            }
        }
        return out.toByteArray();
    }

    public static DiffComputer.FilesManifest decode(byte[] data) throws IOException {
        ArchiveBinaryCodec.Cursor in = new ArchiveBinaryCodec.Cursor(data, "files.bin");
        if (in.i32("magic") != MAGIC) throw new IOException("Invalid files manifest magic");
        int version = in.u8("version");
        if (version != VERSION) throw new IOException("Unsupported files manifest version: " + version);
        long baseEpoch = in.i64("baseEpoch");
        int count = in.count("fileCount");
        var manifest = new DiffComputer.FilesManifest();
        manifest.baseEpoch = baseEpoch;
        manifest.files = new ArrayList<>(count);
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String field = "files[" + i + "]";
            String path = in.string(field + ".path");
            TarIndex.validatePath(path);
            if (!paths.add(path)) throw new IOException(field + ": duplicate path " + path);
            int deleted = in.u8(field + ".deleted");
            if (deleted > 1) throw new IOException(field + ".deleted: invalid value");
            if (deleted == 1) {
                manifest.files.add(new DiffComputer.FilesManifest.FileDiff(path, null, 0,
                        SourceMetadata.empty(), true));
            } else {
                String hash = in.string(field + ".sourceHash");
                long size = in.i64(field + ".size");
                SourceMetadata source = in.metadata(field + ".sourceMetadata");
                if (!hash.matches("[0-9a-f]{32}") || size < 0) throw new IOException(field + ": invalid hash/size");
                manifest.files.add(new DiffComputer.FilesManifest.FileDiff(path, hash, size, source, false));
            }
        }
        in.finish();
        return manifest;
    }
}
