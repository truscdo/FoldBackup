package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.BackupResult;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.core.task.TaskType;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.backup.BackupExecutor;
import io.github.truscdo.foldbackup.mod.i18n.BackupStepFormat;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@code /foldbackup} command for backup management. */
public class FoldBackupCommand {

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
                dispatcher.register(
                                Commands.literal("foldbackup")
                                                .requires(CommandPermissions::canManageBackups)
                                                .then(Commands.literal("backup")
                                                                .executes(ctx -> runBackup(ctx, mod, false))
                                                                .then(Commands.literal("--force")
                                                                                .executes(ctx -> runBackup(ctx, mod,
                                                                                                true))))
                                                .then(Commands.literal("list")
                                                                .executes(ctx -> runList(ctx, mod)))
                                                .then(Commands.literal("status")
                                                                .executes(ctx -> showStatus(ctx, mod)))
                                                .then(Commands.literal("info")
                                                                .then(Commands.argument("id",
                                                                                StringArgumentType.greedyString())
                                                                                .suggests(SnapshotIdSuggestions
                                                                                                .suggestAll(mod::getSnapshotStore))
                                                                                .executes(ctx -> showInfo(ctx, mod,
                                                                                                StringArgumentType
                                                                                                                .getString(ctx, "id")))))
                                                .then(Commands.literal("delete")
                                                                .then(Commands.argument("args",
                                                                                StringArgumentType.greedyString())
                                                                                .suggests(SnapshotIdSuggestions
                                                                                                .suggestForDelete(mod::getSnapshotStore))
                                                                                .executes(ctx -> deleteSnapshots(ctx,
                                                                                                mod,
                                                                                                StringArgumentType
                                                                                                                .getString(ctx, "args")))))
                                                .then(Commands.literal("pin")
                                                                .then(Commands.argument("id",
                                                                                StringArgumentType.greedyString())
                                                                                .suggests(SnapshotIdSuggestions
                                                                                                .suggestAll(mod::getSnapshotStore))
                                                                                .executes(ctx -> setPinned(ctx,
                                                                                                mod,
                                                                                                StringArgumentType
                                                                                                                .getString(ctx, "id"),
                                                                                                true))))
                                                .then(Commands.literal("unpin")
                                                                .then(Commands.argument("id",
                                                                                StringArgumentType.greedyString())
                                                                                .suggests(SnapshotIdSuggestions
                                                                                                .suggestAll(mod::getSnapshotStore))
                                                                                .executes(ctx -> setPinned(ctx,
                                                                                                mod,
                                                                                                StringArgumentType
                                                                                                                .getString(ctx, "id"),
                                                                                                false)))));
        }

        private static int setPinned(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, String id,
                        boolean pinned) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null) {
                        return 0;
                }
                Long epochSeconds = BackendGuard.resolveEpoch(source, mod, store, id);
                if (epochSeconds == null) {
                        return 0;
                }
                String prefix = pinned ? "foldbackup.command.pin." : "foldbackup.command.unpin.";
                mod.setPinnedAsync(epochSeconds, pinned)
                                .whenComplete((changed, exception) -> mod.executeOnServer(() -> {
                                        if (exception != null) {
                                                Throwable cause = exception.getCause() == null ? exception
                                                                : exception.getCause();
                                                FoldBackupMod.LOG.error("Failed to {} snapshot {}",
                                                                pinned ? "pin" : "unpin", id, cause);
                                                CommandFeedback.failure(source, mod, prefix + "failed", id);
                                        } else if (Boolean.TRUE.equals(changed)) {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                prefix + "success", id), true);
                                        } else {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                prefix + "already", id), false);
                                        }
                                }));
                return Command.SINGLE_SUCCESS;
        }

        record DeleteCommandArgs(boolean dryRun, List<String> snapshotIds, String errorKey) {
                boolean valid() {
                        return errorKey == null;
                }
        }

        static DeleteCommandArgs parseDeleteArgs(String rawArgs) {
                if (rawArgs == null || rawArgs.isBlank()) {
                        return new DeleteCommandArgs(false, List.of(), "foldbackup.command.delete.usage");
                }
                boolean dryRun = false;
                Set<String> ids = new LinkedHashSet<>();
                for (String token : rawArgs.trim().split("\\s+")) {
                        if ("--dry-run".equals(token)) {
                                if (dryRun) {
                                        return new DeleteCommandArgs(false, List.of(),
                                                        "foldbackup.command.delete.usage");
                                }
                                dryRun = true;
                        } else if (token.startsWith("--")) {
                                return new DeleteCommandArgs(false, List.of(), "foldbackup.command.delete.usage");
                        } else {
                                ids.add(token);
                        }
                }
                if (ids.isEmpty()) {
                        return new DeleteCommandArgs(false, List.of(), "foldbackup.command.delete.usage");
                }
                return new DeleteCommandArgs(dryRun, List.copyOf(ids), null);
        }

        private static int deleteSnapshots(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, String rawArgs) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null) {
                        return 0;
                }
                DeleteCommandArgs parsed = parseDeleteArgs(rawArgs);
                if (!parsed.valid()) {
                        CommandFeedback.failure(source, mod, parsed.errorKey());
                        return 0;
                }

                List<Long> epochs = new ArrayList<>(parsed.snapshotIds().size());
                for (String id : parsed.snapshotIds()) {
                        Long epochSeconds = BackendGuard.resolveEpoch(source, mod, store, id);
                        if (epochSeconds == null) {
                                return 0;
                        }
                        SnapshotManifest manifest = store.loadSnapshot(epochSeconds);
                        if (manifest == null) {
                                CommandFeedback.failure(source, mod, "foldbackup.command.info.not_found", id);
                                return 0;
                        }
                        if (manifest.isPinned()) {
                                CommandFeedback.failure(source, mod, "foldbackup.command.delete.pinned", id, id);
                                return 0;
                        }
                        epochs.add(epochSeconds);
                }

                if (parsed.dryRun()) {
                        try {
                                SnapshotReclamationEstimator.SubsetDeletionEstimate estimate =
                                                SnapshotReclamationEstimator.estimateSubsetDeletion(
                                                                store.loadAllSnapshots(), epochs,
                                                                store.indexFileSizeBytes());
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.delete.dry_run.title",
                                                estimate.snapshots().size()), false);
                                for (SnapshotReclamationEstimator.SnapshotDeletionItem item : estimate.snapshots()) {
                                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                        "foldbackup.command.delete.dry_run.item",
                                                        item.snapshotId(),
                                                        FormatUtil.formatSize(item.uniqueBytes())), false);
                                }
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.delete.dry_run.summary",
                                                FormatUtil.formatSize(estimate.individualUniqueSumBytes()),
                                                FormatUtil.formatSize(estimate.sharedOnlyAmongSelectedBytes()),
                                                FormatUtil.formatSize(estimate.totalReclaimBytes()),
                                                FormatUtil.formatSize(estimate.repositoryBeforeBytes()),
                                                FormatUtil.formatSize(estimate.repositoryAfterBytes())), false);
                                return Command.SINGLE_SUCCESS;
                        } catch (Exception exception) {
                                FoldBackupMod.LOG.error("Failed to estimate snapshot deletion for {}",
                                                parsed.snapshotIds(), exception);
                                CommandFeedback.failure(source, mod, "foldbackup.command.delete.failed",
                                                String.join(", ", parsed.snapshotIds()));
                                return 0;
                        }
                }

                String displayIds = String.join(", ", parsed.snapshotIds());
                source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.delete.started",
                                displayIds), true);
                mod.deleteSnapshotsAsync(epochs)
                                .whenComplete((gc, exception) -> mod.executeOnServer(() -> {
                                        if (exception != null) {
                                                Throwable cause = exception.getCause() == null ? exception
                                                                : exception.getCause();
                                                if (cause instanceof SnapshotStore.SnapshotPinnedException pinnedEx) {
                                                        CommandFeedback.failure(source, mod,
                                                                        "foldbackup.command.delete.pinned",
                                                                        pinnedEx.snapshotId(), pinnedEx.snapshotId());
                                                        return;
                                                }
                                                FoldBackupMod.LOG.error("Failed to delete snapshot(s) {}",
                                                                displayIds, cause);
                                                CommandFeedback.failure(source, mod, "foldbackup.command.delete.failed",
                                                                displayIds);
                                        } else if (gc != null && !gc.isClean()) {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                "foldbackup.command.delete.cleanup_incomplete",
                                                                displayIds), true);
                                        } else {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                "foldbackup.command.delete.success",
                                                                displayIds), true);
                                        }
                                }));
                return Command.SINGLE_SUCCESS;
        }

        private static int runBackup(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, boolean force) {
                CommandSourceStack source = ctx.getSource();
                final BackupExecutor executor = mod.getExecutor();
                if (executor == null) {
                        CommandFeedback.failure(source, mod, "foldbackup.command.backend_not_ready");
                        return 0;
                }

                AtomicBoolean accepted = new AtomicBoolean();
                CompletableFuture<BackupResult> future = executor.triggerBackup(force, () -> {
                        accepted.set(true);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        force ? "foldbackup.command.backup.start_forced"
                                                        : "foldbackup.command.backup.start"),
                                        false);
                        if (source.getEntity() instanceof ServerPlayer player) {
                                mod.getProgressNotifier().subscribe(player);
                        }
                });

                if (!accepted.get()) {
                        reportBackupOutcome(source, mod, future.getNow(BackupResult.FAILED), null);
                        return 0;
                }

                future.whenComplete((result, throwable) -> mod.executeOnServer(() -> {
                        if (source.getEntity() instanceof ServerPlayer player) {
                                mod.getProgressNotifier().unsubscribe(player.getUUID());
                        }
                        reportBackupOutcome(source, mod, result, throwable);
                }));
                return Command.SINGLE_SUCCESS;
        }

        private static void reportBackupOutcome(
                        CommandSourceStack source, FoldBackupMod mod, BackupResult result, Throwable throwable) {
                if (throwable != null) {
                        FoldBackupMod.LOG.error("Backup command failed", throwable);
                        reportBackupFailureToSource(source, mod);
                        return;
                }
                if (result == null) {
                        reportBackupFailureToSource(source, mod);
                        return;
                }
                switch (result) {
                        case SUCCESS -> source.sendSuccess(
                                        () -> CommandFeedback.text(source, mod,
                                                        "foldbackup.command.backup.success"),
                                        false);
                        case TOO_SOON -> CommandFeedback.failure(source, mod,
                                        "foldbackup.command.backup.too_soon",
                                        mod.getConfig().getMinIntervalMinutes());
                        case ALREADY_RUNNING -> CommandFeedback.failure(source, mod,
                                        "foldbackup.command.backup.already_running");
                        case NOT_INITIALIZED -> CommandFeedback.failure(source, mod,
                                        "foldbackup.command.backend_not_ready");
                        case FAILED -> reportBackupFailureToSource(source, mod);
                }
        }

        /** Player sources already receive the executor's server-wide failure notice. */
        private static void reportBackupFailureToSource(CommandSourceStack source, FoldBackupMod mod) {
                if (!(source.getEntity() instanceof ServerPlayer)) {
                        CommandFeedback.failure(source, mod, "foldbackup.command.backup.failure");
                }
        }

        private static int runList(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null) {
                        return 0;
                }
                try {
                        BackupExporter.SnapshotListView view = new BackupExporter(store).listSnapshotsWithSummary();
                        List<BackupExporter.SnapshotInfo> snapshots = view.snapshots();
                        if (snapshots.isEmpty()) {
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.empty"), false);
                                return 0;
                        }

                        SnapshotReclamationEstimator.RepositorySpaceSummary summary = view.summary();
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.title",
                                        snapshots.size()), false);
                        if (summary.pinnedCount() > 0) {
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.summary_pinned",
                                                FormatUtil.formatSize(summary.storedBytes()),
                                                FormatUtil.formatSize(summary.totalUniqueBytes()),
                                                FormatUtil.formatSize(summary.totalSharedBytes()),
                                                FormatUtil.formatSize(summary.globalSharedBytes()),
                                                summary.pinnedCount(),
                                                FormatUtil.formatSize(summary.pinnedHeldBytes())), false);
                        } else {
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.summary",
                                                FormatUtil.formatSize(summary.storedBytes()),
                                                FormatUtil.formatSize(summary.totalUniqueBytes()),
                                                FormatUtil.formatSize(summary.totalSharedBytes()),
                                                FormatUtil.formatSize(summary.globalSharedBytes())), false);
                        }
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.header"),
                                        false);
                        for (BackupExporter.SnapshotInfo snapshot : snapshots) {
                                String rowId = snapshot.pinned ? snapshot.id + " [PIN]" : snapshot.id;
                                String rowRegions = String.valueOf(snapshot.regions);
                                String rowFiles = String.valueOf(snapshot.files);
                                String rowSize = FormatUtil.formatSize(snapshot.compressedBytes);
                                String rowUnique = FormatUtil.formatSize(snapshot.uniqueBytes);
                                String rowReclaim = snapshot.pinned
                                                ? "-"
                                                : FormatUtil.formatSize(snapshot.cumulativeSpaceBytes);
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.row",
                                                rowId, snapshot.date, rowRegions, rowFiles,
                                                rowSize, rowUnique, rowReclaim), false);
                        }
                } catch (Exception exception) {
                        FoldBackupMod.LOG.error("Failed to list snapshots", exception);
                        CommandFeedback.failure(source, mod, "foldbackup.command.list.failed");
                }
                return Command.SINGLE_SUCCESS;
        }

        private static int showStatus(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod) {
                CommandSourceStack source = ctx.getSource();
                String language = FoldBackupTranslations.resolveLanguage(source, mod.getConfig());
                TaskStatus active = mod.getTaskRegistry().getCurrentActive();
                if (active != null) {
                        String taskType = FoldBackupTranslations.textForLanguage(language, taskTypeKey(active.type()));
                        String progress = BackupStepFormat.formatLocalized(active.stepProgress(), language);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.status.active",
                                        active.taskId(), taskType, progress), false);
                        return Command.SINGLE_SUCCESS;
                }

                List<TaskStatus> history = mod.getTaskRegistry().getHistory();
                if (!history.isEmpty()) {
                        TaskStatus last = history.get(0);
                        String phase = FoldBackupTranslations.textForLanguage(language, phaseKey(last.phase()));
                        String progress = last.stepProgress() == null ? ""
                                        : BackupStepFormat.formatLocalized(last.stepProgress(), language);
                        source.sendSuccess(
                                        () -> CommandFeedback.text(source, mod, "foldbackup.command.status.idle_last",
                                                        last.taskId(), phase, last.elapsedSinceCreated(), progress),
                                        false);
                } else {
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.status.idle"),
                                        false);
                }
                return Command.SINGLE_SUCCESS;
        }

        private static String taskTypeKey(TaskType type) {
                return switch (type) {
                        case BACKUP -> "foldbackup.task_type.backup";
                        case DELETE -> "foldbackup.task_type.delete";
                };
        }

        private static String phaseKey(TaskStatus.Phase phase) {
                return switch (phase) {
                        case PENDING -> "foldbackup.task_phase.pending";
                        case RUNNING -> "foldbackup.task_phase.running";
                        case COMPLETED -> "foldbackup.task_phase.completed";
                        case FAILED -> "foldbackup.task_phase.failed";
                };
        }

        private static int showInfo(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, String id) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null) {
                        return 0;
                }
                SnapshotManifest snapshot = loadSnapshotForInfo(
                                store, id, BackendGuard.reporterFor(source, mod));
                if (snapshot == null) {
                        return 0;
                }

                SnapshotReclamationEstimator.RepositorySpaceAnalysis analysis =
                                SnapshotReclamationEstimator.analyze(store.loadAllSnapshots(),
                                                store.indexFileSizeBytes());
                SnapshotReclamationEstimator.SnapshotSpace space = analysis.spaceFor(snapshot.getEpochSeconds());

                String date = FormatUtil.formatEpoch(snapshot.getEpochSeconds());
                source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.info.title",
                                snapshot.getSnapshotId()), false);
                source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.info.time",
                                date), false);
                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                snapshot.isPinned()
                                                ? "foldbackup.command.info.pinned_yes"
                                                : "foldbackup.command.info.pinned_no"),
                                false);
                if (snapshot.getSummary() != null) {
                        var summary = snapshot.getSummary();
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.regions",
                                        summary.getRegionsTotal(), summary.getRegionsChanged()), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.files",
                                        summary.getExtraFilesTotal(), summary.getExtraFilesChanged()), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.size",
                                        FormatUtil.formatSize(summary.getTotalCompressedBytes()),
                                        FormatUtil.formatSize(summary.getTotalUncompressedBytes())), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.new_data",
                                        FormatUtil.formatSize(summary.getNewBlobBytes())), false);
                }
                if (space != null) {
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.space",
                                        FormatUtil.formatSize(space.uniqueBytes()),
                                        FormatUtil.formatSize(space.sharedBytes()),
                                        FormatUtil.formatSize(analysis.summary().globalSharedBytes())), false);
                }
                return Command.SINGLE_SUCCESS;
        }

        static SnapshotManifest loadSnapshotForInfo(
                        SnapshotStore store, String id, BackendGuard.FailureReporter reporter) {
                try {
                        return BackendGuard.requireSnapshot(store, id, reporter);
                } catch (Exception exception) {
                        FoldBackupMod.LOG.error("Failed to read snapshot info for {}", id, exception);
                        reporter.report("foldbackup.command.info.failed", id);
                        return null;
                }
        }
}
