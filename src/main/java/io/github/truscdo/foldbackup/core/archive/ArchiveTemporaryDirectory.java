package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.FileIo;
import java.io.IOException;
import java.nio.file.*;

/** Task-owned scratch directory; try-with-resources preserves the primary exception. */
final class ArchiveTemporaryDirectory implements AutoCloseable {
    final Path path = Files.createTempDirectory("archive-region-").toAbsolutePath().normalize();
    ArchiveTemporaryDirectory() throws IOException {}
    public void close() throws IOException {
        FileIo.deleteTree(path, false, true, FileIo.DeletePolicy.COLLECT);
    }
}
