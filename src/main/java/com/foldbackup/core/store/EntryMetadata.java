package com.foldbackup.core.store;

import net.openhft.hashing.LongTupleHashFunction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Persisted path, content identity, source attributes, and blob reference. */
public class EntryMetadata {
    public enum Type { REGION, FILE }

    private final Type type;
    private final String filename;
    private final String blobHash;
    private final String blobPath;
    private final long sizeCompressed;
    private final long sizeUncompressed;
    private final SourceMetadata sourceMetadata;
    private String metadataHash;

    public EntryMetadata(Type type, String filename, String blobHash, String blobPath,
                         long sizeCompressed, long sizeUncompressed, SourceMetadata sourceMetadata) {
        this.type = Objects.requireNonNull(type, "type");
        this.filename = Objects.requireNonNull(filename, "filename");
        this.blobHash = Objects.requireNonNull(blobHash, "blobHash");
        this.blobPath = Objects.requireNonNull(blobPath, "blobPath");
        this.sizeCompressed = sizeCompressed;
        this.sizeUncompressed = sizeUncompressed;
        this.sourceMetadata = sourceMetadata == null ? SourceMetadata.empty() : sourceMetadata;
    }

    public Type getType() { return type; }
    public String getFilename() { return filename; }
    public String getBlobHash() { return blobHash; }
    public String getBlobPath() { return blobPath; }
    public long getSizeCompressed() { return sizeCompressed; }
    public long getSizeUncompressed() { return sizeUncompressed; }
    public SourceMetadata getSourceMetadata() { return sourceMetadata; }

    public String getMetadataHash() {
        if (metadataHash == null) metadataHash = computeHash(toBytes());
        return metadataHash;
    }

    /** Binary encoding for index.db v1. Multi-byte values are little-endian. */
    public byte[] toBytes() {
        if (sizeCompressed < 0 || sizeUncompressed < 0)
            throw new IllegalArgumentException("metadata.size: negative");
        byte[] filenameBytes = encode(filename, false, "filename");
        byte[] hashBytes = encode(blobHash, true, "blobHash");
        byte[] blobPathBytes = encode(blobPath, false, "blobPath");
        byte[] sourceBytes = sourceMetadata.toBytes();
        int total = Math.addExact(1 + 2 + filenameBytes.length + 2 + hashBytes.length
                + 2 + blobPathBytes.length + 8 + 8 + 4, sourceBytes.length);
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) (type == Type.REGION ? 0 : 1));
        putString(out, filenameBytes);
        putString(out, hashBytes);
        putString(out, blobPathBytes);
        out.putLong(sizeCompressed).putLong(sizeUncompressed);
        out.putInt(sourceBytes.length).put(sourceBytes);
        return out.array();
    }

    public static EntryMetadata fromBytes(byte[] data) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (!in.hasRemaining()) throw new IOException("metadata.type: missing");
        int ordinal = in.get() & 0xff;
        if (ordinal >= Type.values().length) throw new IOException("metadata.type: invalid " + ordinal);
        Type type = Type.values()[ordinal];
        String filename = readString(in, "filename", false);
        String blobHash = readString(in, "blobHash", true);
        String blobPath = readString(in, "blobPath", false);
        if (in.remaining() < 20) throw new IOException("metadata.sizes: truncated");
        long sizeCompressed = in.getLong();
        long sizeUncompressed = in.getLong();
        int sourceLength = in.getInt();
        if (sourceLength < 2 || sourceLength != in.remaining())
            throw new IOException("metadata.sourceMetadata: invalid length " + sourceLength);
        byte[] sourceBytes = new byte[sourceLength];
        in.get(sourceBytes);
        SourceMetadata sourceMetadata = SourceMetadata.fromBytes(sourceBytes);
        EntryMetadata result = new EntryMetadata(type, filename, blobHash, blobPath,
                sizeCompressed, sizeUncompressed, sourceMetadata);
        try { result.toBytes(); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        return result;
    }

    private static String readString(ByteBuffer in, String field, boolean ascii) throws IOException {
        if (in.remaining() < 2) throw new IOException("metadata." + field + ".length: missing");
        int length = in.getShort() & 0xffff;
        if (length > in.remaining()) throw new IOException("metadata." + field + ": truncated");
        byte[] bytes = new byte[length];
        in.get(bytes);
        return IndexInput.decode(bytes, "metadata." + field, ascii);
    }

    private static void putString(ByteBuffer out, byte[] value) {
        out.putShort((short) value.length).put(value);
    }

    static byte[] encode(String value, boolean ascii, String field) {
        if (value == null) throw new IllegalArgumentException(field + ": null");
        try {
            ByteBuffer encoded = (ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8)
                    .newEncoder().encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() > 65535) throw new IllegalArgumentException(field + ": exceeds 65535 bytes");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(field + ": invalid encoding", e);
        }
    }

    public static String computeHash(byte[] data) {
        long[] hash = LongTupleHashFunction.xx128().hashBytes(data);
        ByteBuffer bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putLong(hash[0]).putLong(hash[1]);
        StringBuilder result = new StringBuilder(32);
        for (byte b : bytes.array()) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof EntryMetadata that
                && getMetadataHash().equals(that.getMetadataHash());
    }

    @Override public int hashCode() { return getMetadataHash().hashCode(); }

    @Override public String toString() {
        return "EntryMetadata{" + type + " " + filename + " hash=" + blobHash + " blob=" + blobPath + "}";
    }
}
