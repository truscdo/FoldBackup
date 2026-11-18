package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Shared little-endian binary reader/writer helpers for archive manifest codecs. */
final class ArchiveBinaryCodec {
    private ArchiveBinaryCodec() {}

    static void writeString(ByteArrayOutputStream out, String value, String context) {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .encode(CharBuffer.wrap(value == null ? "" : value));
            if (encoded.remaining() > 65535) {
                throw new IllegalArgumentException(context + " string exceeds 65535 bytes");
            }
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(context + " string is invalid UTF-8", e);
        }
        out.write(bytes.length & 255);
        out.write((bytes.length >>> 8) & 255);
        out.writeBytes(bytes);
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    static void writeLong(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    static void writeMetadata(ByteArrayOutputStream out, SourceMetadata metadata) {
        byte[] bytes = metadata == null ? SourceMetadata.empty().toBytes() : metadata.toBytes();
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    static final class Cursor {
        private final ByteBuffer input;
        private final String artifactName;

        Cursor(byte[] data, String artifactName) {
            this.input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.artifactName = artifactName;
        }

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
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            } catch (CharacterCodingException e) {
                throw new IOException(field + ": invalid UTF-8", e);
            }
        }

        SourceMetadata metadata(String field) throws IOException {
            int length = i32(field + ".length");
            if (length < 2 || length > input.remaining()) throw new IOException(field + ": invalid length");
            byte[] bytes = new byte[length];
            input.get(bytes);
            return SourceMetadata.fromBytes(bytes);
        }

        void finish() throws IOException {
            if (input.hasRemaining()) throw new IOException(artifactName + " has trailing bytes");
        }
    }
}
