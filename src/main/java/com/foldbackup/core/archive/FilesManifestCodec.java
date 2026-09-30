package com.foldbackup.core.archive;

import com.foldbackup.core.store.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
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
            writeString(out, file.path);
            out.write(file.deleted ? 1 : 0);
            if (!file.deleted) {
                writeString(out, file.sourceHash);
                out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(file.sizeUncompressed).array());
                byte[] metadata = file.sourceMetadata.toBytes();
                out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
                out.writeBytes(metadata);
            }
        }
        return out.toByteArray();
    }

    public static DiffComputer.FilesManifest decode(byte[] data) throws IOException {
        Cursor in = new Cursor(data);
        if (in.i32("magic") != MAGIC) throw new IOException("Invalid files manifest magic");
        int version = in.u8("version");
        if (version != VERSION) throw new IOException("Unsupported files manifest version: " + version);
        long baseEpoch = in.i64("baseEpoch");
        int count = in.i32("fileCount");
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid fileCount: " + count);
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

    private static void writeString(ByteArrayOutputStream out, String value) {
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value == null ? "" : value));
            if (encoded.remaining() > 65535) throw new IllegalArgumentException("files.bin string too long");
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            out.write(bytes.length & 255); out.write(bytes.length >>> 8); out.writeBytes(bytes);
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("files.bin invalid UTF-8", e); }
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
        long i64(String field) throws IOException {
            if (input.remaining() < 8) throw new IOException(field + ": truncated");
            return input.getLong();
        }
        String string(String field) throws IOException {
            if (input.remaining() < 2) throw new IOException(field + ": missing length");
            int len = input.getShort() & 65535;
            if (len > input.remaining()) throw new IOException(field + ": truncated");
            ByteBuffer bytes = input.slice(); bytes.limit(len); input.position(input.position() + len);
            try { return StandardCharsets.UTF_8.newDecoder().decode(bytes).toString(); }
            catch (CharacterCodingException e) { throw new IOException(field + ": invalid UTF-8", e); }
        }
        SourceMetadata metadata(String field) throws IOException {
            int len = i32(field + ".length");
            if (len < 2 || len > input.remaining()) throw new IOException(field + ": invalid length");
            byte[] bytes = new byte[len]; input.get(bytes); return SourceMetadata.fromBytes(bytes);
        }
        void finish() throws IOException { if (input.hasRemaining()) throw new IOException("files.bin has trailing bytes"); }
    }
}
