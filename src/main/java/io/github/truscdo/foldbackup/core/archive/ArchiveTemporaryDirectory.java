package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/** Task-owned scratch directory; try-with-resources preserves the primary exception. */
final class ArchiveTemporaryDirectory implements AutoCloseable {
    final Path path = Files.createTempDirectory("archive-region-").toAbsolutePath().normalize();
    ArchiveTemporaryDirectory() throws IOException {}
    public void close() throws IOException {
        java.util.List<Path> entries;
        try (var walk = Files.walk(path)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        } // Close directory enumeration handles before deleting on Windows.
        IOException failure = null;
        for (Path entry : entries) {
            if (!entry.toAbsolutePath().normalize().startsWith(path))
                throw new IOException("Scratch path escaped its root");
            try { delete(entry); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }

    private static void delete(Path entry) throws IOException {
        // Windows can briefly retain delete-pending children after their handles close.
        // Finish bounded cleanup even on cancellation, then restore the interrupt flag.
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; ; attempt++) {
                try { Files.deleteIfExists(entry); return; }
                catch (DirectoryNotEmptyException | AccessDeniedException e) {
                    if (attempt == 19) throw e;
                    try { Thread.sleep(25); }
                    catch (InterruptedException cancellation) { interrupted = true; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
