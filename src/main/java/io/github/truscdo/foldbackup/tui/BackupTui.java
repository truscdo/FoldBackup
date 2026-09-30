package io.github.truscdo.foldbackup.tui;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.console.ProgressPrinter;
import io.github.truscdo.foldbackup.core.console.SnapshotTablePrinter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 交互式 TUI（终端用户界面），用于备份管理。
 * <p>
 * 提供编号菜单界面，用于列出和导出快照，无需记住 CLI 参数。
 * 设计通过 {@code foldbackup-tui.bat} 或 {@code foldbackup-tui.sh} 脚本启动。
 * <p>
 * 备份根目录由调用方显式指定，仓库在选择操作后才初始化。
 */
public class BackupTui {

    private final Path backupRoot;
    private final Path archiveRoot;
    private final ArchiveTui archiveOperations;
    private final ConsolePrompt prompt;

    private record StoreContext(SnapshotStore snapshotManager, BlobStore blobStore, BackupExporter exporter) {}

    public BackupTui(Path backupRoot, Path archiveRoot, ConsolePrompt prompt) {
        if (backupRoot == null && archiveRoot == null) {
            throw new IllegalArgumentException("At least one TUI root is required");
        }
        this.backupRoot = backupRoot;
        this.archiveRoot = archiveRoot;
        this.prompt = prompt;
        this.archiveOperations = archiveRoot != null ? new ArchiveTui(archiveRoot, backupRoot, prompt) : null;
    }

    private StoreContext openStore() throws IOException {
        var store = new SnapshotStore(backupRoot);
        var blobs = new BlobStore(backupRoot, ArchiveDefaults.EXPORT_LEVEL);
        var backupExporter = new BackupExporter(store, blobs, ArchiveDefaults.EXPORT_LEVEL);
        return new StoreContext(store, blobs, backupExporter);
    }

    private void runStoreTask(Consumer<StoreContext> task) {
        if (backupRoot == null) {
            System.out.println("Online backup root is not configured; this operation is unavailable.\n");
            return;
        }
        try {
            task.accept(openStore());
        } catch (IOException e) {
            System.err.println("Error loading backup store: " + e.getMessage());
        }
    }


    // ──────────────────────────────────────────────
    // 入口
    // ──────────────────────────────────────────────

    /**
     * TUI 入口——只接收调用方明确指定并验证的备份根。
     * 由 {@code BackupCli} 在 {@code tui} 分发时调用。
     */
    public static void launch(Path root, Path archiveRoot) {
        LogUtil.setPlatform(LogUtil.Platform.TUI);

        var prompt = new ConsolePrompt(new java.io.InputStreamReader(System.in), System.out);
        new BackupTui(root, archiveRoot, prompt).run();
    }

    // ──────────────────────────────────────────────
    // 主循环
    // ──────────────────────────────────────────────

    public void run() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("  FoldBackup - Backup Management Tool");
        printRootStatus();
        System.out.println("========================================");
        System.out.println();

        while (true) {
            printMenu();
            System.out.println("  0. Exit");
            System.out.print("Enter a number: ");

            String line = prompt.readLine("");
            if (line == null) { // EOF：stdin 关闭，优雅退出
                System.out.println("Goodbye!");
                return;
            }
            line = line.trim();
            int choice;
            try {
                choice = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Please enter a number.\n");
                continue;
            }

            if (choice >= 6 && choice <= 8 && archiveOperations == null) {
                System.out.println("Archive root is not configured; this operation is unavailable.\n");
                continue;
            }
            switch (choice) {
                case 1 -> runStoreTask(this::listSnapshots);
                case 2 -> runStoreTask(this::exportWorld);
                case 3 -> runStoreTask(this::deleteSnapshot);
                case 4 -> runStoreTask(this::createArchive);
                case 5 -> createSnapshot();
                case 6 -> archiveOperations.exportArchive();
                case 7 -> archiveOperations.importArchives();
                case 8 -> archiveOperations.verifyArchives();
                case 0 -> {
                    System.out.println("Goodbye!");
                    return;
                }
                default -> System.out.println("Invalid choice. Choose a displayed option or 0 to exit.\n");
            }
        }
    }

    private void printRootStatus() {
        if (backupRoot == null) {
            System.out.println("  Online backup: DISABLED (root not provided)");
        } else {
            System.out.println("  Online backup: ENABLED  " + backupRoot.toAbsolutePath().normalize());
        }
        if (archiveRoot == null) {
            System.out.println("  Archive storage:  DISABLED (root not provided)");
        } else {
            System.out.println("  Archive storage:  ENABLED  " + archiveRoot.toAbsolutePath().normalize());
        }
    }

    private void printMenu() {
        System.out.println("Online backups");
        System.out.println("  1. List snapshots" + (backupRoot == null ? " [disabled]" : ""));
        System.out.println("  2. Export world files" + (backupRoot == null ? " [disabled]" : ""));
        System.out.println("  3. Delete snapshot" + (backupRoot == null ? " [disabled]" : ""));
        System.out.println("  4. Create archive" + (backupRoot == null ? " [disabled]" : ""));
        System.out.println("  5. Create snapshot from world" + (backupRoot == null ? " [disabled]" : ""));
        System.out.println();
        System.out.println("----------------------------------------");
        System.out.println();
        System.out.println("Archive storage");
        String archiveDisabled = archiveRoot == null ? " [disabled]" : "";
        System.out.println("  6. Restore as world files" + archiveDisabled);
        System.out.println("  7. Import into online backup" + archiveDisabled);
        System.out.println("  8. Verify archives" + archiveDisabled);
        System.out.println();
    }

    // ──────────────────────────────────────────────
    // 列表
    // ──────────────────────────────────────────────

    private void createSnapshot() {
        if (backupRoot == null) {
            System.out.println("Online backup root is not configured; this operation is unavailable.");
            return;
        }
        String input = prompt.readLine("World directory (Enter = back): ");
        if (input == null || input.isBlank()) return;
        int threads = prompt.readThreadCount();
        if (threads == ConsolePrompt.EOF || threads == ConsolePrompt.BACK) return;
        System.out.println("Snapshot root: " + backupRoot);
        System.out.println("The world must be closed before creating a snapshot.");
        try {
            SnapshotManifest snapshot;
            try (var progress = new ProgressPrinter(System.err)) {
                snapshot = SnapshotCreation.create(
                        Path.of(input.trim()), backupRoot, threads, progress.onProgress());
            }
            System.out.println(SnapshotCreation.summary(snapshot, backupRoot));
        } catch (IOException | RuntimeException e) {
            System.err.println("Error creating snapshot: " + e.getMessage());
        }
    }

    private void listSnapshots(StoreContext store) {
        List<BackupExporter.SnapshotInfo> snapshots = store.exporter().listSnapshotsWithCumulativeSpace();
        if (snapshots.isEmpty()) {
            System.out.println("No snapshots found.\n");
            return;
        }

        System.out.println();
        System.out.println("Snapshots (" + snapshots.size() + "):");
        SnapshotTablePrinter.printList(snapshots, System.out);
        System.out.println();
    }

    // ──────────────────────────────────────────────
    // 导出
    // ──────────────────────────────────────────────

    private void exportWorld(StoreContext store) {
        List<BackupExporter.SnapshotInfo> snapshots = store.exporter().listSnapshots();
        if (snapshots.isEmpty()) {
            System.out.println("No snapshots to export.\n");
            return;
        }

        while (true) {
            System.out.println();
            SnapshotTablePrinter.printSelection(snapshots, System.out);
            System.out.println();

            int snapChoice = prompt.readInt("Select a snapshot by number (0 = back): ", 0, snapshots.size());
            if (snapChoice <= 0) {
                return;
            }
            BackupExporter.SnapshotInfo selected = snapshots.get(snapChoice - 1);

            // 0 here returns to snapshot selection.
            while (true) {
                System.out.println();
                int zipChoice = prompt.readInt(
                        "Package as zip? (1 = yes, 2 = no, 0 = back): ", 0, 2);
                if (zipChoice <= 0) {
                    break;
                }
                boolean zip = zipChoice == 1;

                // 0 here returns to the ZIP choice.
                while (true) {
                    System.out.println();
                    Path defaultOutput = ArchiveDefaults.worldExport(selected.id);
                    String pathStr = prompt.readLine("Output directory (Enter for default: "
                            + defaultOutput + ", 0 = back): ");
                    if (pathStr == null) {
                        return;
                    }
                    pathStr = pathStr.trim();
                    if ("0".equals(pathStr)) {
                        break;
                    }

                    Path outputPath;
                    try {
                        outputPath = pathStr.isEmpty() ? defaultOutput : Path.of(pathStr);
                    } catch (java.nio.file.InvalidPathException e) {
                        System.out.println("Invalid output path: " + e.getMessage() + "\n");
                        continue;
                    }

                    int threads = prompt.readThreadCount();
                    if (threads == ConsolePrompt.EOF) return;
                    if (threads == ConsolePrompt.BACK) continue;

                    System.out.println();
                    System.out.println("Summary:");
                    System.out.println("  Snapshot: " + selected.id);
                    System.out.println("  Format:   MCA (.mca)");
                    System.out.println("  Zip:      " + (zip ? "yes" : "no"));
                    System.out.println("  Threads:  " + (threads > 0 ? threads : "auto"));
                    System.out.println("  Output:   " + outputPath);
                    System.out.println();

                    int confirm = prompt.readInt(
                            "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
                    if (confirm == 2) {
                        System.out.println("Export cancelled.\n");
                        return;
                    }
                    if (confirm <= 0) {
                        continue;
                    }

                    System.out.println("Exporting...");
                    try {
                        Path result;
                        try (var progress = new ProgressPrinter(System.out)) {
                            result = store.exporter().exportWithProgress(selected.epochSeconds, outputPath,
                                    BackupExporter.Format.MCA, zip, threads, progress.onProgress());
                        }
                        System.out.println("Done: " + result.toAbsolutePath().normalize() + "\n");
                    } catch (IOException e) {
                        System.err.println("Export failed: " + e.getMessage() + "\n");
                    }
                    return;
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 删除
    // ──────────────────────────────────────────────

    private void deleteSnapshot(StoreContext store) {
        List<BackupExporter.SnapshotInfo> snapshots = store.exporter().listSnapshots();
        if (snapshots.isEmpty()) {
            System.out.println("No snapshots to delete.\n");
            return;
        }

        while (true) {
            System.out.println();
            SnapshotTablePrinter.printSelection(snapshots, System.out);
            System.out.println();

            int snapChoice = prompt.readInt(
                    "Select a snapshot to delete by number (0 = back): ", 0, snapshots.size());
            if (snapChoice <= 0) {
                return;
            }
            BackupExporter.SnapshotInfo selected = snapshots.get(snapChoice - 1);

            System.out.println();
            System.out.println("WARNING: This will permanently delete the snapshot and its blobs.");
            System.out.println("  Snapshot: " + selected.id);
            System.out.println();

            int confirm = prompt.readInt(
                    "Are you sure? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
            if (confirm == 2) {
                System.out.println("Delete cancelled.\n");
                return;
            }
            if (confirm <= 0) {
                continue;
            }

            System.out.println("Deleting...");
            try {
                store.snapshotManager().deleteSnapshot(selected.epochSeconds);
                System.out.println("Snapshot deleted: " + selected.id + "\n");
            } catch (IOException e) {
                System.err.println("Delete failed: " + e.getMessage() + "\n");
            }
            return;
        }
    }

    private void createArchive(StoreContext store) {
        List<BackupExporter.SnapshotInfo> snapshots = store.exporter().listSnapshots();
        if (snapshots.isEmpty()) {
            System.out.println("No snapshots available for archive creation.\n");
            return;
        }

        while (true) {
            // 1. Select target snapshot.
            System.out.println();
            SnapshotTablePrinter.printSelection(snapshots, System.out);
            System.out.println();
            int targetChoice = prompt.readInt(
                    "Select target snapshot by number (0 = back): ", 0, snapshots.size());
            if (targetChoice <= 0) {
                return;
            }
            BackupExporter.SnapshotInfo target = snapshots.get(targetChoice - 1);

            // 0 returns to target selection.
            while (true) {
                System.out.println();
                int typeChoice = prompt.readInt(
                        "Archive type (1 = diff, 2 = full, 0 = back): ", 0, 2);
                if (typeChoice <= 0) {
                    break;
                }

                boolean exitMenu = typeChoice == 1
                        ? selectAndCreateDiff(store, target)
                        : selectAndCreateFull(store, target);
                if (exitMenu) return;
            }
        }
    }

    /** Returns true when the caller should leave the archive create menu. */
    private boolean selectAndCreateDiff(StoreContext store, BackupExporter.SnapshotInfo target) {
        List<BackupExporter.SnapshotInfo> bases = store.exporter().diffBaseCandidates(target.epochSeconds);
        if (bases.isEmpty()) {
            System.out.println("No earlier snapshots can be used as a diff base.\n");
            return false;
        }

        while (true) {
            System.out.println();
            System.out.println("Base snapshots earlier than target " + target.id + ":");
            SnapshotTablePrinter.printSelection(bases, System.out);
            System.out.println();
            int baseChoice = prompt.readInt(
                    "Select base snapshot by number (0 = back): ", 0, bases.size());
            if (baseChoice <= 0) return false;

            BackupExporter.SnapshotInfo base = bases.get(baseChoice - 1);
            int threads = prompt.readThreadCount();
            if (threads == ConsolePrompt.EOF) return true;
            if (threads == ConsolePrompt.BACK) continue;

            System.out.println();
            System.out.println("Summary:");
            System.out.println("  Type:   diff");
            System.out.println("  Base:   " + base.id);
            System.out.println("  Target: " + target.id);
            System.out.println("  Threads: " + (threads > 0 ? threads : "auto"));
            System.out.println("  Output: " + archiveOutputRoot());
            System.out.println();
            int confirm = prompt.readInt(
                    "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
            if (confirm == 2) {
                System.out.println("Archive cancelled.\n");
                return true;
            }
            if (confirm <= 0) continue;

            runArchiveCreate(store, base.epochSeconds, base.id, target.epochSeconds, target.id, true, threads);
            return true;
        }
    }

    /** Returns true when the caller should leave the archive create menu. */
    private boolean selectAndCreateFull(StoreContext store, BackupExporter.SnapshotInfo target) {
        while (true) {
            System.out.println();
            int recompressChoice = prompt.readInt(
                    "Recompress regions at level 19? (1 = yes, 2 = no, 0 = back): ", 0, 2);
            if (recompressChoice <= 0) return false;

            boolean recompress = recompressChoice == 1;
            int threads = prompt.readThreadCount();
            if (threads == ConsolePrompt.EOF) return true;
            if (threads == ConsolePrompt.BACK) continue;

            System.out.println();
            System.out.println("Summary:");
            System.out.println("  Type:       full");
            System.out.println("  Target:     " + target.id);
            System.out.println("  Recompress: " + (recompress ? "yes (level 19)" : "no (verbatim)")
                    + (recompress && threads > 0 ? ", threads=" + threads : ""));
            System.out.println("  Output:     " + archiveOutputRoot());
            System.out.println();
            int confirm = prompt.readInt(
                    "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
            if (confirm == 2) {
                System.out.println("Archive cancelled.\n");
                return true;
            }
            if (confirm <= 0) continue;

            runArchiveCreate(store, null, null, target.epochSeconds, target.id, recompress, threads);
            return true;
        }
    }

    private void runArchiveCreate(StoreContext store, Long baseEpoch, String baseId,
            long targetEpoch, String targetId, boolean recompress, int threads) {
        var archiveCreator = new ArchiveCreator(
                store.snapshotManager(), store.blobStore(), ArchiveDefaults.ARCHIVE_LEVEL);
        Path outputRoot = archiveOutputRoot();
        try {
            Path result;
            try (var progress = new ProgressPrinter(System.out)) {
                if (baseId != null) {
                    System.out.println("Creating diff archive " + baseId + " → " + targetId + " ...");
                    result = archiveCreator.createDiffWithProgress(baseEpoch, targetEpoch, outputRoot, threads,
                            progress.onProgress());
                } else {
                    System.out.println("Creating full archive " + targetId + " ...");
                    result = archiveCreator.createFullWithProgress(targetEpoch, outputRoot, recompress, threads,
                            progress.onProgress());
                }
            }
            System.out.println("Done: " + result.toAbsolutePath().normalize() + "\n");
        } catch (IOException e) {
            System.err.println("Archive creation failed: " + e.getMessage() + "\n");
        }
    }

    /** Archive creation belongs to online backup management, so it can use the online root as output. */
    private Path archiveOutputRoot() {
        return archiveRoot != null ? archiveRoot : backupRoot;
    }


}
