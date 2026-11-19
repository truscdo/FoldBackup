package io.github.truscdo.foldbackup.tui;

import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.archive.ArchiveFormatUtil;
import io.github.truscdo.foldbackup.core.archive.ArchiveImporter;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestoreSource;
import io.github.truscdo.foldbackup.core.archive.ArchiveTarScanner;
import io.github.truscdo.foldbackup.core.archive.ArchiveTarScanner.ArchiveTarInfo;
import io.github.truscdo.foldbackup.core.archive.ArchiveVerifier;
import io.github.truscdo.foldbackup.core.console.ArchiveVerifyReporter;
import io.github.truscdo.foldbackup.core.console.ProgressPrinter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Archive actions used by the shared TUI menu. */
public class ArchiveTui {

    private final Path archiveRoot;
    private final Path onlineRoot;
    private final ConsolePrompt prompt;

    public ArchiveTui(Path archiveRoot, Path onlineRoot, ConsolePrompt prompt) {
        this.archiveRoot = Objects.requireNonNull(archiveRoot, "archiveRoot");
        this.onlineRoot = Objects.requireNonNull(onlineRoot, "onlineRoot");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    void exportArchive() {
        while (true) {
            SelectedArchive selected = selectArchive();
            if (selected == null) {
                return;
            }

            String targetId = selected.target().meta().sourceSnapshotId;
            Path defaultOutput = ArchiveDefaults.worldExport(targetId);
            var flow = RestoreInteraction.runFromPathPrompt(prompt, "World files directory", defaultOutput,
                    () -> ArchiveRestoreSource.openResolved(selected.fullTar(), selected.diffTar()),
                    ArchiveDefaults.RESTORE_LEVEL, "Restoring...", "Restore cancelled.", "Restore failed: ");
            if (flow != RestoreInteraction.Flow.BACK_TO_PATH) {
                return;
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
            result = new ArchiveVerifier().verifyLocated(
                    selected.fullTar(), selected.diffTar(), progress.onProgress());
        } catch (IOException e) {
            System.err.println("Verify failed: " + e.getMessage() + "\n");
            return;
        }
        ArchiveVerifyReporter.print(result, System.out);
        System.out.println();
    }

    void importArchives() {
        Path targetRoot = onlineRoot;

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
        Path fullTar() {
            return full.tar();
        }

        Path diffTar() {
            return target.meta().isDiff() ? target.tar() : null;
        }
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
                    ? FormatUtil.formatEpoch(t.meta().baseEpoch) : "-";
            System.out.printf("%-4d %-8s %-22s %-30s %-22s %8d %8d %10s%n",
                    i + 1, t.meta().type, FormatUtil.formatEpoch(t.meta().epoch),
                    t.meta().sourceSnapshotId, baseEpoch, t.meta().regionCount,
                    t.meta().chunkCount, FormatUtil.formatSize(t.size()));
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

        Path fullPath;
        try {
            fullPath = ArchiveTarScanner.fullAtEpoch(tars, target.meta().baseEpoch);
        } catch (IOException e) {
            String message = ArchiveFormatUtil.baselineArchiveErrorMessage(target.meta().baseEpoch, e);
            if (!ArchiveFormatUtil.isConflictingFullError(e)) {
                message += " for diff " + target.meta().sourceSnapshotId
                        + ". Put the full archive in " + archiveRoot + " and retry.";
            }
            System.out.println(message + "\n");
            return null;
        }
        ArchiveTarInfo full = tars.stream()
                .filter(t -> t.tar().equals(fullPath))
                .findFirst()
                .orElseThrow();
        return new SelectedArchive(full, target);
    }
}
