package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.LrhdFormat;
import io.github.truscdo.foldbackup.core.format.StreamIo;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;

/**
 * Detects the standard or encoding LRHD layout inside a file region and exposes a
 * stream in logical standard order. Both layouts are validated in full; a region that
 * matches neither, or matches both with different byte orders, is rejected.
 */
public final class LrhdLayoutSource {
    private LrhdLayoutSource() {}

    /** Opens the whole region at {@code (archive, offset, size)} in logical standard order. */
    public static InputStream open(Path archive, long offset, long size) throws IOException {
        FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ);
        try {
            if (size < LrhdFormat.HEADER_SIZE + LrhdFormat.FOOTER_SIZE)
                throw new EOFException("Truncated LRHD layout");
            long[] encoding = validateEncoding(channel, offset, size);
            boolean standard = validateStandard(channel, offset, size);
            // An empty frame and index make both layouts byte-identical; treat that as standard.
            if (encoding != null && encoding[0] == 0 && encoding[1] == 0) encoding = null;
            if (standard == (encoding != null)) throw new IOException("Invalid or ambiguous LRHD layout");
            if (standard) return identity(channel, offset, size);
            return new ReorderedInput(channel, offset, size, encoding[0], encoding[1]);
        } catch (IOException | RuntimeException | Error e) {
            channel.close();
            throw e;
        }
    }

    /** Opens a TAR entry, returning a logical standard-order stream. */
    static InputStream open(TarIndex.Entry entry) throws IOException {
        return open(entry.archive(), entry.offset(), entry.size());
    }

    private static boolean validateStandard(FileChannel channel, long offset, long size) throws IOException {
        try {
            byte[] header = new byte[LrhdFormat.HEADER_SIZE];
            readAt(channel, offset, ByteBuffer.wrap(header));
            var metadata = LrhdFormat.validateHeader(header, size);
            long indexLength = indexLength(metadata);
            if (LrhdFormat.HEADER_SIZE + indexLength + metadata.compressedDataLength()
                    + LrhdFormat.FOOTER_SIZE != size) return false;
            try (var input = new SequenceInputStream(new ByteArrayInputStream(header),
                    range(channel, offset + LrhdFormat.HEADER_SIZE, indexLength))) {
                LrhdFormat.verifyIndex(input, size);
            }
            return true;
        } catch (IOException e) {
            if (e instanceof InterruptedIOException) throw e;
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return {@code {indexLength, frameLength}} when valid, otherwise {@code null}. */
    private static long[] validateEncoding(FileChannel channel, long offset, long size) throws IOException {
        try {
            byte[] header = new byte[LrhdFormat.HEADER_SIZE];
            readAt(channel, offset, ByteBuffer.wrap(header, 0, LrhdFormat.SIGNATURE_SIZE));
            long metadataOffset = size - LrhdFormat.FOOTER_SIZE - LrhdFormat.METADATA_SIZE;
            readAt(channel, offset + metadataOffset,
                    ByteBuffer.wrap(header, LrhdFormat.SIGNATURE_SIZE, LrhdFormat.METADATA_SIZE));
            var metadata = LrhdFormat.validateHeader(header, size);
            ByteBuffer footer = ByteBuffer.allocate(LrhdFormat.FOOTER_SIZE);
            readAt(channel, offset + size - LrhdFormat.FOOTER_SIZE, footer);
            if (footer.flip().getLong() != LrhdFormat.SIGNATURE) return null;
            long indexLength = indexLength(metadata);
            long indexOffset = LrhdFormat.SIGNATURE_SIZE + metadata.compressedDataLength();
            if (indexLength != metadataOffset - indexOffset) return null;
            try (var input = new SequenceInputStream(new ByteArrayInputStream(header),
                    range(channel, offset + indexOffset, indexLength))) {
                LrhdFormat.verifyIndex(input, size);
            }
            return new long[] {indexLength, metadata.compressedDataLength()};
        } catch (IOException e) {
            if (e instanceof InterruptedIOException) throw e;
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long indexLength(LrhdFormat.Header header) {
        return (long) header.regionCount() * LrhdFormat.REGION_INDEX_SIZE
                + (long) header.changedChunkCount() * LrhdFormat.CHUNK_INDEX_RECORD_SIZE;
    }

    private static InputStream identity(FileChannel channel, long offset, long size) throws IOException {
        channel.position(offset);
        InputStream bounded = StreamIo.limited(Channels.newInputStream(channel), size);
        return new FilterInputStream(bounded) {
            @Override public void close() throws IOException { channel.close(); }
        };
    }

    /** Non-owning bounded view of a file region; valid only while the channel stays open. */
    private static InputStream range(FileChannel channel, long offset, long length) {
        return new InputStream() {
            private long position;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }

            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0) return 0;
                long remaining = length - position;
                if (remaining <= 0) return -1;
                int n = (int) Math.min(len, remaining);
                readAt(channel, offset + position, ByteBuffer.wrap(bytes, off, n));
                position += n;
                return n;
            }

            @Override public void close() {}
        };
    }

    private static void readAt(FileChannel channel, long offset, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            StreamIo.checkCancelled();
            int n = channel.read(buffer, offset);
            if (n < 0) throw new EOFException("Truncated LRHD layout");
            offset += n;
        }
    }

    /** Reorders {@code [signature, frame, index, metadata, footer]} into standard order. */
    private static final class ReorderedInput extends InputStream {
        private final FileChannel channel;
        private final long base, size, indexLength, frameLength;
        private int part;
        private long position;

        ReorderedInput(FileChannel channel, long base, long size, long indexLength, long frameLength) {
            this.channel = channel; this.base = base; this.size = size;
            this.indexLength = indexLength; this.frameLength = frameLength;
        }

        private long offsetOf(int part) {
            return switch (part) {
                case 0 -> 0;
                case 1 -> size - LrhdFormat.FOOTER_SIZE - LrhdFormat.METADATA_SIZE;
                case 2 -> LrhdFormat.SIGNATURE_SIZE + frameLength;
                case 3 -> LrhdFormat.SIGNATURE_SIZE;
                default -> size - LrhdFormat.FOOTER_SIZE;
            };
        }

        private long lengthOf(int part) {
            return switch (part) {
                case 0 -> LrhdFormat.SIGNATURE_SIZE;
                case 1 -> LrhdFormat.METADATA_SIZE;
                case 2 -> indexLength;
                case 3 -> frameLength;
                default -> LrhdFormat.FOOTER_SIZE;
            };
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, bytes.length);
            if (len == 0) return 0;
            while (part < 5 && position == lengthOf(part)) { part++; position = 0; }
            if (part == 5) return -1;
            int n = (int) Math.min(len, lengthOf(part) - position);
            readAt(channel, base + offsetOf(part) + position, ByteBuffer.wrap(bytes, off, n));
            position += n;
            return n;
        }

        @Override public void close() throws IOException { channel.close(); }
    }
}
