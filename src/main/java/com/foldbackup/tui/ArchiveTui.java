package com.foldbackup.tui;

import com.foldbackup.core.archive.ArchiveDefaults;
import com.foldbackup.core.archive.ArchiveFormatUtil;
import com.foldbackup.core.archive.ArchiveImporter;
import com.foldbackup.core.archive.ArchiveRestorer;
import com.foldbackup.core.archive.ArchiveTarScanner;
import com.foldbackup.core.archive.ArchiveTarScanner.ArchiveTarInfo;
import com.foldbackup.core.archive.ArchiveVerifier;
import com.foldbackup.core.console.ArchiveVerifyReporter;
import com.foldbackup.core.console.ProgressPrinter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Archive actions used by the shared TUI menu. */
public class ArchiveTui {

    private final Path archiveRoot;
    private final Path onlineRoot;
    private final ConsolePrompt prompt;

    public ArchiveTui(Path archiveRoot, Path onlineRoot, ConsolePrompt prompt) {
        this.archiveRoot = archiveRoot;
        this.onlineRoot = onlineRoot;
        this.prompt = prompt;
    }

    void exportArchive() {
        while (true) {
            SelectedArchive selected = selectArchive();
            if (selected == null) {
                return;
            }

            String targetId = selected.target().meta().sourceSnapshotId;
            String diffId = selected.target().meta().isDiff() ? targetId : null;

            // 0 returns to selecting the archive.
            while (true) {
                System.out.println();
                Path defaultOutput = ArchiveDefaults.worldExport(targetId);
                String pathStr = prompt.readLine("World files directory (Enter for default: "
                        + defaultOutput + ", 0 = back): ");
                if (pathStr == null) {
                    return;
                }
                pathStr = pathStr.trim();
                if ("0".equals(pathStr)) {
                    break;
                }

                Path output;
                try {
                    output = pathStr.isEmpty() ? defaultOutput : Path.of(pathStr);
                } catch (java.nio.file.InvalidPathException e) {
                    System.out.println("Invalid output path: " + e.getMessage() + "\n");
                    continue;
                }

                while (true) {
                    System.out.println();
                    System.out.println("Summary:");
                    System.out.println("  Full:   " + selected.full().fileName());
                    if (diffId != null) {
                        System.out.println("  Diff:   " + selected.target().fileName());
                    }
                    System.out.println("  Output: " + output);
                    System.out.println();

                    int confirm = prompt.readInt(
                            "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
                    if (confirm == 2) {
                        System.out.println("Restore cancelled.\n");
                        return;
                    }
                    if (confirm <= 0) {
                        break;
                    }

                    int threads = prompt.readThreadCount();
                    if (threads == ConsolePrompt.EOF) threads = 0;
                    if (threads == ConsolePrompt.BACK) continue;
                    try {
                        var restorer = new ArchiveRestorer(ArchiveDefaults.RESTORE_LEVEL, threads);
                        Path result;
                        try (var progress = new ProgressPrinter(System.out)) {
                            System.out.println("Restoring " + (diffId != null ? "full+" + diffId : "full") + " ...");
                            result = restorer.restore(archiveRoot, selected.full().meta().epoch,
                                    selected.target().meta().isDiff() ? selected.target().meta().epoch : null,
                                    output, progress.onProgress());
                        }
                        System.out.println("Done: " + result.toAbsolutePath().normalize() + "\n");
                    } catch (IOException e) {
                        System.err.println("Restore failed: " + e.getMessage() + "\n");
                    }
                    return;
                }
            }
        }
    }

    void verifyArchives() {
        SelectedArchive selected = selectArchive();
        if (selected == null) {
            return;
        }

        ArchiveVerifier.Result result;
        try (var progress = new ProgressPrinter(System.out)) {
            result = new ArchiveVerifier().verify(archiveRoot, selected.full().meta().epoch,
                    selected.target().meta().isDiff() ? selected.target().meta().epoch : null,
                    progress.onProgress());
        } catch (IOException e) {
            System.err.println("Verify failed: " + e.getMessage() + "\n");
            return;
        }
        ArchiveVerifyReporter.print(result, System.out);
        System.out.println();
    }

    void importArchives() {
        // With no explicit online root, the archive root is also the import target.
        // This allows a archive-only directory to be rebuilt as an online repository.
        Path targetRoot = onlineRoot != null ? onlineRoot : archiveRoot;

        while (true) {
            System.out.println();
            System.out.println("Import archives into the online backup root.");
            System.out.println("  Source: " + archiveRoot.toAbsolutePath().normalize());
            System.out.println("  Target: " + targetRoot.toAbsolutePath().normalize());
            System.out.println();

            int confirm = prompt.readInt(
                    "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
            if (confirm != 1) {
                System.out.println("Import cancelled.\n");
                return;
            }

            int threads = prompt.readThreadCount();
            if (threads == ConsolePrompt.EOF) threads = 0;
            if (threads == ConsolePrompt.BACK) continue;
            int imported;
            try (var progress = new ProgressPrinter(System.out)) {
                imported = new ArchiveImporter(archiveRoot, targetRoot, threads).importAll(progress.onProgress());
            } catch (IOException e) {
                System.err.println("Import failed: " + e.getMessage() + "\n");
                return;
            }
            System.out.println("Imported " + imported + " snapshot(s) into " + targetRoot + "\n");
            return;
        }
    }

    /** A selected target plus the full archive required to read it. */
    private record SelectedArchive(ArchiveTarInfo full, ArchiveTarInfo target) {
    }

    /**
     * Lists all valid full/diff archives. Selecting a diff automatically resolves
     * its baseline full by epoch, as required by the TUI flow.
     */
    private SelectedArchive selectArchive() {
        List<ArchiveTarInfo> tars = ArchiveTarScanner.scan(archiveRoot);
        List<ArchiveTarInfo> archives = tars.stream()
                .filter(t -> t.meta() != null && (t.meta().isFull() || t.meta().isDiff()))
                .toList();
        if (archives.isEmpty()) {
            System.out.println("No archives found in " + archiveRoot + "\n");
            return null;
        }

        System.out.println();
        System.out.printf("%-4s %-8s %-22s %-30s %-22s %8s %8s %10s%n",
                "#", "Type", "Epoch", "Source", "BaseEpoch", "Regions", "Chunks", "Size");
        System.out.println("-".repeat(130));
        for (int i = 0; i < archives.size(); i++) {
            ArchiveTarInfo t = archives.get(i);
            String baseEpoch = t.meta().isDiff()
                    ? ArchiveFormatUtil.formatEpoch(t.meta().baseEpoch) : "-";
            System.out.printf("%-4d %-8s %-22s %-30s %-22s %8d %8d %10s%n",
                    i + 1, t.meta().type, ArchiveFormatUtil.formatEpoch(t.meta().epoch),
                    t.meta().sourceSnapshotId, baseEpoch, t.meta().regionCount,
                    t.meta().chunkCount, ArchiveFormatUtil.formatSize(t.size()));
        }
        System.out.println();

        int choice = prompt.readInt("Select archive by number (0 = back): ", 0, archives.size());
        if (choice <= 0) {
            return null;
        }

        ArchiveTarInfo target = archives.get(choice - 1);
        if (target.meta().isFull()) {
            return new SelectedArchive(target, target);
        }

        ArchiveTarInfo full = tars.stream()
                .filter(t -> t.meta() != null && t.meta().isFull()
                        && t.meta().epoch == target.meta().baseEpoch)
                .findFirst()
                .orElse(null);
        if (full == null) {
            System.out.println("No corresponding full archive found for diff "
                    + target.meta().sourceSnapshotId + ". Put the full archive in "
                    + archiveRoot + " and retry.\n");
            return null;
        }
        return new SelectedArchive(full, target);
    }
}
