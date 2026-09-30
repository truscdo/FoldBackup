package com.foldbackup.core.archive;

import com.foldbackup.core.StreamingHash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streams decoded file bytes and computes their content identity. */
final class FileContentIdentity {
    record Summary(long size, String hash) {
        boolean matches(long expectedSize, String expectedHash) {
            return size == expectedSize && expectedHash.equals(hash);
        }
    }

    private FileContentIdentity() {}

    static Summary inspect(InputStream input) throws IOException {
        return read(input, null);
    }

    static Summary copy(InputStream input, OutputStream output) throws IOException {
        return read(input, output);
    }

    private static Summary read(InputStream input, OutputStream output) throws IOException {
        var hash = new StreamingHash.Hash128();
        long length = 0;
        byte[] buffer = new byte[65536];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) continue;
            if (output != null) output.write(buffer, 0, count);
            hash.update(buffer, 0, count);
            length = Math.addExact(length, count);
        }
        return new Summary(length, hash.fileHex());
    }
}
