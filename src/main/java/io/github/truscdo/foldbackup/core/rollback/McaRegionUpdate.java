package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** A one-cell MCA update whose source region is forwarded without materializing it. */
record McaRegionUpdate(Path source, int regionX, int regionZ, long mtimeMillis,
                       int targetIndex, byte[] replacement, int targetTimestamp) {
    private static final int COPY_BUFFER = 64 * 1024;

    McaRegionUpdate {
        Objects.requireNonNull(source, "source");
        if (targetIndex < 0 || targetIndex >= 1024) throw new IllegalArgumentException("Invalid target index");
    }

    RegionStream.Source stream() {
        return consumer -> {
            if (!Files.exists(source) || Files.size(source) == 0) {
                writeEmpty(consumer);
                return;
            }

            int[] nextIndex = {0};
            boolean[] targetSeen = {false};
            boolean[] replacing = {false};
            McaStream.read(source, new RegionStream.Consumer() {
                @Override public void begin(int index, int timestamp) throws IOException {
                    if (index != nextIndex[0]) throw new IOException("Unexpected MCA event index " + index);
                    replacing[0] = index == targetIndex;
                    targetSeen[0] |= replacing[0];
                    consumer.begin(index, replacing[0] ? targetTimestamp : timestamp);
                }

                @Override public void data(byte[] buffer, int offset, int length) throws IOException {
                    if (!replacing[0]) consumer.data(buffer, offset, length);
                }

                @Override public void end() throws IOException {
                    if (replacing[0]) writeReplacement(consumer);
                    consumer.end();
                    nextIndex[0]++;
                }
            });
            if (nextIndex[0] != 1024 || !targetSeen[0])
                throw new IOException("MCA stream did not contain all 1024 cells: " + source);
        };
    }

    private void writeEmpty(RegionStream.Consumer consumer) throws IOException {
        for (int index = 0; index < 1024; index++) {
            consumer.begin(index, index == targetIndex ? targetTimestamp : 0);
            if (index == targetIndex) writeReplacement(consumer);
            consumer.end();
        }
    }

    private void writeReplacement(RegionStream.Consumer consumer) throws IOException {
        if (replacement == null) return;
        for (int offset = 0; offset < replacement.length; offset += COPY_BUFFER) {
            int length = Math.min(COPY_BUFFER, replacement.length - offset);
            consumer.data(replacement, offset, length);
        }
    }

    static long mtime(Path source) throws IOException {
        return Files.exists(source) ? Files.getLastModifiedTime(source).toMillis() : System.currentTimeMillis();
    }
}
