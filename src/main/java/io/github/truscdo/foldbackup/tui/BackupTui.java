package io.github.truscdo.foldbackup.tui;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.store.BlobMaintenance;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.console.ProgressPrinter;
import io.github.truscdo.foldbackup.core.console.SnapshotTablePrinter;
import io.github.truscdo.foldbackup.core.export.SnapshotZipExport;
import io.github.truscdo.foldbackup.core.restore.SnapshotRestoreSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 交互式 TUI（终端用户界面），用于备份管理。
 * <p>
 * 提供编号菜单界面，用于列出和导出快照，无需记住 CLI 参数。
 * 可由用户按 README 提供的内容创建启动脚本，或直接运行 {@code tui} 子命令。
 * <p>
 * 两个根目录（在线备份根与归档根）均由调用方显式指定且不允许为空，
 * 仓库在选择操作后才初始化。
 */
public class BackupTui {

    private final Path backupRoot;
    private final Path archiveRoot;
    private final ArchiveTui archiveOperations;
    private final ConsolePrompt prompt;

    private record StoreContext(SnapshotStore snapshotManager, BlobStore blobStore, BackupExporter exporter) {}

    public BackupTui(Path backupRoot, Path archiveRoot, ConsolePrompt prompt) {
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot");
        this.archiveRoot = Objects.requireNonNull(archiveRoot, "archiveRoot");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.archiveOperations = new ArchiveTui(archiveRoot, backupRoot, prompt);
    }

    private StoreContext openStore() throws IOException {
        var store = new SnapshotStore(backupRoot);
        var blobs = new BlobStore(backupRoot, ArchiveDefaults.EXPORT_LEVEL);
        var backupExporter = new BackupExporter(store);
        return new StoreContext(store, blobs, backupExporter);
    }

    private void runStoreTask(Consumer<StoreContext> task) {
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
     * TUI 入口——接收调用方指定的在线备份根与归档根，两者都必须提供。
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
        System.out.println("  Online backup: " + backupRoot.toAbsolutePath().normalize());
        System.out.println("  Archive root:  " + archiveRoot.toAbsolutePath().normalize());
    }

    private void printMenu() {
        System.out.println("Online backups");
        System.out.println("  1. List snapshots");
        System.out.println("  2. Export world files");
        System.out.println("  3. Delete snapshot");
        System.out.println("  4. Create archive");
        System.out.println("  5. Create snapshot from world");
        System.out.println();
        System.out.println("----------------------------------------");
        System.out.println();
        System.out.println("Archive storage");
        System.out.println("  6. Restore as world files");
        System.out.println("  7. Import into online backup");
        System.out.println("  8. Verify archives");
        System.out.println();
    }

    // ──────────────────────────────────────────────
    // 列表
    // ──────────────────────────────────────────────

    private void createSnapshot() {
        String input = prompt.readLine("World directory (Enter = back): ");
        if (input == null || input.isBlank()) return;
        int threads = prompt.readThreadCount();
        if (threads == ConsolePrompt.EOF || threads == ConsolePrompt.BACK) return;
        System.out.println("Snapshot root: " + backupRoot);
        System.out.println("The world must be closed before creating a snapshot.");
        try {
            SnapshotManifest snapshot;
            try (var progress = new ProgressPrinter(System.out)) {
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
                    Path defaultOutput = zip
                            ? ArchiveDefaults.worldExportZip(selected.id)
                            : ArchiveDefaults.worldExport(selected.id);
                    RestoreInteraction.OutputPath choice = RestoreInteraction.promptOutputPath(
                            prompt, zip ? "Output zip path" : "Output directory", defaultOutput);
                    if (choice.isEof()) {
                        return;
                    }
                    if (choice.isBack()) {
                        break;
                    }
                    Path outputPath = choice.path();

                    if (!zip) {
                        var flow = exportDirectory(store, selected, outputPath);
                        if (flow == RestoreInteraction.Flow.CANCEL) {
                            System.out.println("Export cancelled.\n");
                            return;
                        }
                        if (flow == RestoreInteraction.Flow.DONE) return;
                        continue;
                    }

                    Path zipTarget;
                    try {
                        zipTarget = SnapshotZipExport.resolveTarget(outputPath);
                    } catch (IOException e) {
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
                    System.out.println("  Zip:      yes");
                    System.out.println("  Threads:  " + (threads > 0 ? threads : "auto"));
                    System.out.println("  Output:   " + zipTarget);
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
                            result = SnapshotZipExport.export(store.snapshotManager(), store.blobStore(),
                                    selected.id, zipTarget, threads, progress.onProgress());
                        }
                        System.out.println("Done: " + result.toAbsolutePath().normalize() + "\n");
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Export failed: " + e.getMessage() + "\n");
                    }
                    return;
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 目录导出（检查 → 统计 → 决策 → 线程数 → 执行）
    // ──────────────────────────────────────────────

    private RestoreInteraction.Flow exportDirectory(
            StoreContext store, BackupExporter.SnapshotInfo selected, Path outputPath) {
        SnapshotManifest manifest = store.snapshotManager().loadSnapshot(selected.epochSeconds);
        if (manifest == null) {
            System.err.println("Snapshot not found: " + selected.id + "\n");
            return RestoreInteraction.Flow.CANCEL;
        }
        return RestoreInteraction.run(prompt, () -> new SnapshotRestoreSource(manifest, store.blobStore()),
                outputPath, ArchiveDefaults.EXPORT_LEVEL, "Exporting...", "Export failed: ");
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
                BlobMaintenance.GcResult gc = store.snapshotManager().deleteSnapshot(selected.epochSeconds);
                System.out.println("Snapshot deleted: " + selected.id);
                if (!gc.isClean()) {
                    System.out.println("Warning: cleanup of unused backup files was incomplete (failed="
                            + gc.failedFiles() + ", scanComplete=" + gc.scanComplete() + ").");
                }
                System.out.println();
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
            var decision = confirmArchiveCreate(threads -> List.of(
                    "  Type:   diff",
                    "  Base:   " + base.id,
                    "  Target: " + target.id,
                    "  Threads: " + (threads > 0 ? threads : "auto"),
                    "  Output: " + archiveRoot));
            if (decision.action() == ArchiveConfirm.BACK) continue;
            if (decision.action() == ArchiveConfirm.LEAVE) return true;

            runArchiveCreate(store, base.epochSeconds, base.id, target.epochSeconds, target.id, true, decision.threads());
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
            var decision = confirmArchiveCreate(threads -> List.of(
                    "  Type:       full",
                    "  Target:     " + target.id,
                    "  Recompress: " + (recompress ? "yes (level 19)" : "no (verbatim)")
                            + (recompress && threads > 0 ? ", threads=" + threads : ""),
                    "  Output:     " + archiveRoot));
            if (decision.action() == ArchiveConfirm.BACK) continue;
            if (decision.action() == ArchiveConfirm.LEAVE) return true;

            runArchiveCreate(store, null, null, target.epochSeconds, target.id, recompress, decision.threads());
            return true;
        }
    }

    private enum ArchiveConfirm { PROCEED, BACK, LEAVE }
    private record ArchiveCreateDecision(ArchiveConfirm action, int threads) {}

    /** 提示线程数、打印摘要并读取归档创建最终确认。 */
    private ArchiveCreateDecision confirmArchiveCreate(java.util.function.IntFunction<List<String>> summaryLines) {
        int threads = prompt.readThreadCount();
        if (threads == ConsolePrompt.EOF) return new ArchiveCreateDecision(ArchiveConfirm.LEAVE, 0);
        if (threads == ConsolePrompt.BACK) return new ArchiveCreateDecision(ArchiveConfirm.BACK, 0);

        System.out.println();
        System.out.println("Summary:");
        for (String line : summaryLines.apply(threads)) {
            System.out.println(line);
        }
        System.out.println();
        int confirm = prompt.readInt(
                "Proceed? (1 = confirm, 2 = cancel to main menu, 0 = back): ", 0, 2);
        if (confirm == 2) {
            System.out.println("Archive cancelled.\n");
            return new ArchiveCreateDecision(ArchiveConfirm.LEAVE, 0);
        }
        if (confirm <= 0) return new ArchiveCreateDecision(ArchiveConfirm.BACK, 0);
        return new ArchiveCreateDecision(ArchiveConfirm.PROCEED, threads);
    }

    private void runArchiveCreate(StoreContext store, Long baseEpoch, String baseId,
            long targetEpoch, String targetId, boolean recompress, int threads) {
        var archiveCreator = new ArchiveCreator(
                store.snapshotManager(), store.blobStore(), ArchiveDefaults.ARCHIVE_LEVEL);
        Path outputRoot = archiveRoot;
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
        } catch (IOException | RuntimeException e) {
            System.err.println("Archive creation failed: " + e.getMessage() + "\n");
        }
    }
}
