package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.archive.ArchiveRestoreSource;
import io.github.truscdo.foldbackup.core.archive.ArchiveTarScanner;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Locates an archive by epoch and restores it through the shared skeleton.
 * <p>
 * Tests used to drive {@code ArchiveRestorer} directly with epoch arguments; this keeps that
 * shape while going through the same code path the CLI and TUI use.
 */
public final class RestoreTestSupport {
    private RestoreTestSupport() {
    }

    /** The archive file for the given epochs: the diff when {@code diffEpoch} is set, else the full. */
    public static Path locate(Path archiveRoot, Long baseEpoch, Long diffEpoch) throws IOException {
        var tars = ArchiveTarScanner.scan(archiveRoot);
        if (diffEpoch != null) {
            if (baseEpoch == null) throw new IOException("A base epoch is required with a diff epoch");
            return ArchiveTarScanner.diffAtEpoch(tars, baseEpoch, diffEpoch);
        }
        if (baseEpoch != null) return ArchiveTarScanner.fullAtEpoch(tars, baseEpoch);
        var fulls = ArchiveTarScanner.fulls(tars);
        if (fulls.isEmpty()) throw new IOException("No full archive found in " + archiveRoot);
        return ArchiveTarScanner.fullAtEpoch(tars, fulls.get(0).meta().epoch);
    }

    public static RestoreOperation.Outcome restore(Path archiveRoot, Long baseEpoch, Long diffEpoch,
            Path output, boolean deleteExtra, int threads) throws IOException {
        try (var source = ArchiveRestoreSource.open(archiveRoot, locate(archiveRoot, baseEpoch, diffEpoch))) {
            return RestoreOperation.run(source, output,
                    new RestoreOperation.Options(false, deleteExtra, threads, 6), null);
        }
    }

    public static Path restoreTo(Path archiveRoot, Long baseEpoch, Long diffEpoch, Path output,
            boolean deleteExtra, int threads) throws IOException {
        return restore(archiveRoot, baseEpoch, diffEpoch, output, deleteExtra, threads).target();
    }
}
