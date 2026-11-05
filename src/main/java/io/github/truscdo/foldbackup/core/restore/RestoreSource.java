package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Content behind one restore input: an online snapshot or an archive.
 * <p>
 * The checker only reads {@link #entries()}; content is requested during the write phase.
 * Region content is pushed in source order because compressed frames cannot be reopened at an
 * arbitrary offset: implementations may batch, but they never require the caller to replay a
 * frame.
 */
public interface RestoreSource extends AutoCloseable {

    /** Every path the target must contain, in a stable order. */
    List<RestoreEntry> entries();

    /** Raw bytes of a regular file entry. The caller closes the stream. */
    InputStream openFile(RestoreEntry entry) throws IOException;

    /**
     * Runs {@code consumer} for the selected regions. Implementations decide batching and
     * threading; the consumer may be invoked concurrently and must write distinct paths.
     */
    void readRegions(List<RestoreEntry> regions, RegionConsumer consumer, int threads) throws IOException;

    @FunctionalInterface
    interface RegionConsumer {
        void accept(RestoreEntry entry, RegionStream.Source source) throws IOException;
    }

    @Override
    default void close() throws IOException {
    }
}
