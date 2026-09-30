package com.foldbackup.core.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.file.attribute.BasicFileAttributes;

/** Source filesystem attributes used to skip content reads when a path is unchanged. */
public record SourceMetadata(List<SourceMetadata.SourceFile> files) {
    private static final int MAX_FILES = 1025;
    private static final SourceMetadata EMPTY = new SourceMetadata(List.of());

    public SourceMetadata {
        Objects.requireNonNull(files, "files");
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("source metadata has too many files");
        List<SourceFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(SourceFile::relativePath));
        Set<String> paths = new HashSet<>();
        for (SourceFile file : sorted) {
            Objects.requireNonNull(file, "source metadata file");
            if (!paths.add(file.relativePath()))
                throw new IllegalArgumentException("duplicate source metadata path: " + file.relativePath());
        }
        files = List.copyOf(sorted);
    }

    public static SourceMetadata empty() { return EMPTY; }

    public static SourceMetadata of(String relativePath, BasicFileAttributes attributes) {
        return new SourceMetadata(List.of(SourceFile.of(relativePath, attributes)));
    }

    public boolean isEmpty() { return files.isEmpty(); }

    /** Binary representation used by online and archive manifests. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, files.size());
        for (SourceFile file : files) {
            writeString(out, file.relativePath());
            out.writeBytes(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(file.size()).putLong(file.modifiedMillis()).array());
            writeString(out, file.fileKey());
        }
        return out.toByteArray();
    }

    public static SourceMetadata fromBytes(byte[] data) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < 2) throw new IOException("sourceMetadata.count: missing");
        int count = input.getShort() & 0xffff;
        if (count > MAX_FILES) throw new IOException("sourceMetadata.count: invalid " + count);
        List<SourceFile> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String field = "sourceMetadata.files[" + i + "]";
            String path = readString(input, field + ".path");
            if (input.remaining() < 16) throw new IOException(field + ": truncated attributes");
            long size = input.getLong();
            long modifiedMillis = input.getLong();
            if (size < 0) throw new IOException(field + ".size: negative");
            String fileKey = readString(input, field + ".fileKey");
            try {
                files.add(new SourceFile(path, size, modifiedMillis, fileKey));
            } catch (IllegalArgumentException e) {
                throw new IOException(field + ": " + e.getMessage(), e);
            }
        }
        if (input.hasRemaining()) throw new IOException("sourceMetadata: trailing data");
        try {
            return new SourceMetadata(files);
        } catch (IllegalArgumentException e) {
            throw new IOException("sourceMetadata: " + e.getMessage(), e);
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = EntryMetadata.encode(value, false, "sourceMetadata.string");
        writeShort(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static String readString(ByteBuffer input, String field) throws IOException {
        if (input.remaining() < 2) throw new IOException(field + ".length: missing");
        int length = input.getShort() & 0xffff;
        if (length > input.remaining()) throw new IOException(field + ": truncated");
        byte[] bytes = new byte[length];
        input.get(bytes);
        return IndexInput.decode(bytes, field);
    }

    public record SourceFile(String relativePath, long size, long modifiedMillis, String fileKey) {
        public SourceFile {
            Objects.requireNonNull(relativePath, "relativePath");
            if (relativePath.isEmpty()) throw new IllegalArgumentException("relativePath must not be empty");
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
            fileKey = fileKey == null ? "" : fileKey;
        }

        public static SourceFile of(String relativePath, BasicFileAttributes attributes) {
            Objects.requireNonNull(attributes, "attributes");
            return new SourceFile(relativePath, attributes.size(), attributes.lastModifiedTime().toMillis(),
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString());
        }
    }
}
