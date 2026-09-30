package com.foldbackup.core.format;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounds Zstd decoding to one frame so read-ahead cannot hide trailing data. */
final class LrhdSingleFrameInput extends InputStream {
    private final InputStream input;
    private byte[] pending;
    private int cursor, payload;
    private boolean last, checksum;

    LrhdSingleFrameInput(InputStream input) throws IOException {
        this.input = input;
        byte[] prefix = exact(5);
        if (prefix[0] != 0x28 || prefix[1] != (byte) 0xb5 || prefix[2] != 0x2f || prefix[3] != (byte) 0xfd
                || (prefix[4] & 4) == 0 || (prefix[4] & 0x18) != 0)
            throw new IOException("LRHD requires a Zstd frame with checksum");
        int descriptor = prefix[4] & 255;
        boolean single = (descriptor & 32) != 0;
        int fcsFlag = descriptor >>> 6, dictFlag = descriptor & 3;
        int contentSizeBytes = fcsFlag == 0 ? (single ? 1 : 0) : 1 << fcsFlag;
        int dictBytes = dictFlag == 3 ? 4 : dictFlag;
        byte[] rest = exact((single ? 0 : 1) + dictBytes + contentSizeBytes);
        pending = Arrays.copyOf(prefix, prefix.length + rest.length);
        System.arraycopy(rest, 0, pending, prefix.length, rest.length);
    }

    private byte[] exact(int count) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) throw new EOFException("Truncated LRHD Zstd frame");
        return bytes;
    }

    @Override public int read() throws IOException {
        byte[] bytes = new byte[1];
        return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 255;
    }

    @Override public int read(byte[] bytes, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (cursor < pending.length) {
            int n = Math.min(len, pending.length - cursor);
            System.arraycopy(pending, cursor, bytes, off, n);
            cursor += n;
            return n;
        }
        if (payload > 0) {
            int n = input.read(bytes, off, Math.min(len, payload));
            if (n <= 0) throw new EOFException("Truncated LRHD Zstd block");
            payload -= n;
            return n;
        }
        if (last) {
            if (checksum) return -1;
            pending = exact(4);
            checksum = true;
        } else {
            pending = exact(3);
            int block = (pending[0] & 255) | (pending[1] & 255) << 8 | (pending[2] & 255) << 16;
            last = (block & 1) != 0;
            int type = (block >>> 1) & 3;
            if (type == 3) throw new IOException("Invalid LRHD Zstd block type");
            payload = type == 1 ? 1 : block >>> 3;
        }
        cursor = 0;
        return read(bytes, off, len);
    }
}
