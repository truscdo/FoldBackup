package com.foldbackup.core.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Bounded, field-aware reader for the index envelope (big-endian). */
final class IndexInput implements AutoCloseable {
    private final DataInputStream in;
    private long remaining;

    IndexInput(InputStream in, long size) {
        this.in = new DataInputStream(in);
        remaining = size;
    }

    byte[] bytes(int size, String field) throws IOException {
        if (size < 0 || size > remaining) throw new IOException(field + ": invalid length " + size);
        byte[] data = new byte[size];
        try { in.readFully(data); }
        catch (IOException e) { throw new IOException(field + ": truncated or unreadable", e); }
        remaining -= size;
        return data;
    }

    int u8(String field) throws IOException { return bytes(1, field)[0] & 255; }
    int u16(String field) throws IOException { return ByteBuffer.wrap(bytes(2, field)).getShort() & 65535; }
    int i32(String field) throws IOException { return ByteBuffer.wrap(bytes(4, field)).getInt(); }
    long i64(String field) throws IOException { return ByteBuffer.wrap(bytes(8, field)).getLong(); }
    int count(String field, int minimumBytes) throws IOException {
        int value = i32(field);
        if (value < 0 || value > remaining / minimumBytes)
            throw new IOException(field + ": invalid count " + value);
        return value;
    }
    String utf(String field) throws IOException { return decode(bytes(u16(field + ".length"), field), field); }
    static String decode(byte[] bytes, String field) throws IOException {
        return decode(bytes, field, false);
    }
    static String decode(byte[] bytes, String field, boolean ascii) throws IOException {
        Charset charset = ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
        try { return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException(field + ": invalid UTF-8", e); }
    }
    void end(String field) throws IOException {
        if (remaining != 0 || in.read() != -1) throw new IOException(field + ": trailing data");
    }
    @Override public void close() throws IOException { in.close(); }
}
