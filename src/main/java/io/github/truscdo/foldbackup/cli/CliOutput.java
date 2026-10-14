package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestorer;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** CLI-only staging and no-overwrite publication for exported save files. */
final class CliOutput {
    private CliOutput() {}

    static Path exportSnapshot(SnapshotStore snapshots, BackupExporter exporter, String snapshotId,
            Path requestedOutput, boolean zip, int threads, ProgressListener listener) throws IOException {
        Path target = checkedTarget(requestedOutput, zip);
        Path stage = createStage(target);
        Path stagedZip = stage.resolveSibling(stage.getFileName() + ".zip");
        try {
            long epoch = snapshots.resolveEpoch(snapshotId);
            Path result = exporter.exportWithProgress(epoch, stage, zip, threads, listener);
            if (zip) {
                Files.move(result, target);
            } else {
                Files.move(stage, target);
            }
            return target;
        } finally {
            deleteTree(stage);
            Files.deleteIfExists(stagedZip);
        }
    }

    static Path exportArchive(ArchiveRestorer restorer, Path archiveRoot, Path archiveFile,
            Path requestedOutput, ProgressListener listener) throws IOException {
        Path target = checkedTarget(requestedOutput, false);
        Path stage = createStage(target);
        try {
            restorer.restoreArchive(archiveRoot, archiveFile, stage, listener);
            Files.move(stage, target);
            return target;
        } finally {
            deleteTree(stage);
        }
    }

    private static Path checkedTarget(Path requested, boolean zip) throws IOException {
        if (requested == null || requested.toString().isBlank())
            throw new IOException("Output path is required");
        Path target = requested.toAbsolutePath().normalize();
        if (target.getFileName() == null) throw new IOException("Output path must name a file or directory");
        if (zip && !target.getFileName().toString().endsWith(".zip"))
            throw new IOException("ZIP output path must end with .zip: " + requested);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Output already exists: " + target);
        return target;
    }

    private static Path createStage(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Output path has no parent: " + target);
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "." + target.getFileName() + ".foldbackup-");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
