package com.foldbackup.core.format;

import com.github.luben.zstd.ZstdOutputStream;
import com.foldbackup.core.StreamingHash;
import java.io.*;

/** Single-use, unknown-length input; does not own the caller's streams. */
public final class ZstdStream {
    private ZstdStream() {}
    public record Summary(String hash, long length) {}
    public static Summary write(InputStream input, OutputStream output, int level) throws IOException {
        var hash = new StreamingHash.Hash128();
        byte[] buffer = new byte[65536];
        long length = 0;
        OutputStream nonClosing = StreamIo.nonClosingOutput(output);
        try (var zstd = new ZstdOutputStream(nonClosing, level)) {
            for (int n; (n = input.read(buffer)) != -1;) {
                StreamIo.checkCancelled(); hash.update(buffer, 0, n); zstd.write(buffer, 0, n);
                length = Math.addExact(length, n);
            }
        }
        return new Summary(hash.fileHex(), length);
    }
}
