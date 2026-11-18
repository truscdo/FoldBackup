package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.StreamingHash;

import java.io.IOException;
import java.io.InputStream;

/** Streams decoded file bytes and computes their content identity. */
final class FileContentIdentity {
    record Summary(long size, String hash) {
        boolean matches(long expectedSize, String expectedHash) {
            return size == expectedSize && expectedHash.equals(hash);
        }
    }

    private FileContentIdentity() {}

    static Summary inspect(InputStream input) throws IOException {
        var hash = new StreamingHash.Hash128();
        long length = StreamingHash.read(input, hash);
        return new Summary(length, hash.fileHex());
    }
}
