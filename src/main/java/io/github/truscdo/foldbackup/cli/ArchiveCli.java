package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestorer;
import io.github.truscdo.foldbackup.core.console.ProgressPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Archive creation and world export commands routed from {@link BackupCli}. */
public final class ArchiveCli {

    private ArchiveCli() {}

    public static void runArchiveCreate(Path snapshotRoot, Path archiveRoot, List<String> args)
            throws IOException {
        if (!Files.isDirectory(snapshotRoot)) throw new IOException("Snapshot root not found: " + snapshotRoot);
        String snapshotId = null;
        String baseId = null;
        int level = ArchiveDefaults.ARCHIVE_LEVEL;
        int threads = 0;
        boolean recompress = true;
        boolean snapshotSeen = false;
        boolean baseSeen = false;
        boolean levelSeen = false;
        boolean threadsSeen = false;
        boolean recompressSeen = false;
        for (int i = 2; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--snapshot" -> {
                    if (snapshotSeen) throw new IOException("Duplicate --snapshot");
                    snapshotId = optionValue(args, ++i, "--snapshot");
                    snapshotSeen = true;
                }
                case "--base" -> {
                    if (baseSeen) throw new IOException("Duplicate --base");
                    baseId = optionValue(args, ++i, "--base");
                    baseSeen = true;
                }
                case "--level" -> {
                    if (levelSeen) throw new IOException("Duplicate --level");
                    level = parsePositiveOption(args, ++i, "--level");
                    levelSeen = true;
                }
                case "--threads" -> {
                    if (threadsSeen) throw new IOException("Duplicate --threads");
                    threads = parsePositiveOption(args, ++i, "--threads");
                    threadsSeen = true;
                }
                case "--no-recompress" -> {
                    if (recompressSeen) throw new IOException("Duplicate --no-recompress");
                    recompress = false;
                    recompressSeen = true;
                }
                default -> throw new IOException("Unknown archive create option: " + args.get(i));
            }
        }
        if (snapshotId == null) throw new IOException("archive create requires --snapshot <snapshot-id>");

        SnapshotStore snapshots = new SnapshotStore(snapshotRoot);
        BlobStore blobs = new BlobStore(snapshotRoot, ArchiveDefaults.EXPORT_LEVEL);
        ArchiveCreator exporter = new ArchiveCreator(snapshots, blobs, level);
        long targetEpoch = snapshots.resolveEpoch(snapshotId);
        Long baseEpoch = baseId == null ? null : snapshots.resolveEpoch(baseId);
        if (baseId != null && !recompress)
            System.out.println("Note: --no-recompress applies to full archives only; ignored for diff.");

        Path result;
        try (var progress = new ProgressPrinter(System.err)) {
            if (baseEpoch == null) {
                System.out.println("Creating full archive for " + snapshotId + " in " + archiveRoot + " ...");
                result = exporter.createFullWithProgress(targetEpoch, archiveRoot, recompress, threads,
                        progress.onProgress());
            } else {
                System.out.println("Creating diff archive from " + baseId + " to " + snapshotId
                        + " in " + archiveRoot + " ...");
                result = exporter.createDiffWithProgress(baseEpoch, targetEpoch, archiveRoot, threads,
                        progress.onProgress());
            }
        }
        System.out.println("Done: " + result);
    }

    public static void runArchiveExport(Path archiveRoot, List<String> args) throws IOException {
        if (args.size() < 3) throw new IOException("Usage: archive export <archive-file> --output <dir> [--threads <N>]");
        Path archiveFile = BackupCli.resolveArchiveFile(archiveRoot, args.get(2));
        Path output = null;
        int threads = 0;
        boolean outputSeen = false;
        boolean threadsSeen = false;
        for (int i = 3; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--output" -> {
                    if (outputSeen) throw new IOException("Duplicate --output");
                    output = Path.of(optionValue(args, ++i, "--output"));
                    outputSeen = true;
                }
                case "--threads" -> {
                    if (threadsSeen) throw new IOException("Duplicate --threads");
                    threads = parsePositiveOption(args, ++i, "--threads");
                    threadsSeen = true;
                }
                default -> throw new IOException("Unknown archive export option: " + args.get(i));
            }
        }
        if (output == null) throw new IOException("archive export requires --output <dir>");
        ArchiveRestorer restorer = new ArchiveRestorer(ArchiveDefaults.RESTORE_LEVEL, threads);
        Path result;
        try (var progress = new ProgressPrinter(System.err)) {
            System.out.println("Exporting " + archiveFile + " to " + output + " ...");
            result = CliOutput.exportArchive(restorer, archiveRoot, archiveFile, output, progress.onProgress());
        }
        System.out.println("Done: " + result);
    }

    private static String optionValue(List<String> args, int index, String option) throws IOException {
        if (index >= args.size() || args.get(index).startsWith("--"))
            throw new IOException(option + " requires a value");
        return args.get(index);
    }

    private static int parsePositiveOption(List<String> args, int index, String option) throws IOException {
        String value = optionValue(args, index, option);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new IOException(option + " must be a positive integer");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException(option + " requires an integer", e);
        }
    }

}
