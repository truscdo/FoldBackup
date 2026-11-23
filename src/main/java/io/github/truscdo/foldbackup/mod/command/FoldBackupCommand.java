package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.BackupResult;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
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

import java.util.List;
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
                                                                .then(Commands.argument("id",
                                                                                StringArgumentType.greedyString())
                                                                                .suggests(SnapshotIdSuggestions
                                                                                                .suggestAll(mod::getSnapshotStore))
                                                                                .executes(ctx -> deleteSnapshot(ctx,
                                                                                                mod,
                                                                                                StringArgumentType
                                                                                                                .getString(ctx, "id")))))
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
                                .whenComplete((changed, exception) -> mod.getServer().execute(() -> {
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

        private static int deleteSnapshot(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, String id) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null) {
                        return 0;
                }
                Long epochSeconds = BackendGuard.resolveEpoch(source, mod, store, id);
                if (epochSeconds == null) {
                        return 0;
                }
                SnapshotManifest manifest = store.loadSnapshot(epochSeconds);
                if (manifest != null && manifest.isPinned()) {
                        CommandFeedback.failure(source, mod, "foldbackup.command.delete.pinned", id, id);
                        return 0;
                }
                source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.delete.started", id),
                                true);
                mod.deleteSnapshotAsync(epochSeconds)
                                .whenComplete((gc, exception) -> mod.getServer().execute(() -> {
                                        if (exception != null) {
                                                Throwable cause = exception.getCause() == null ? exception
                                                                : exception.getCause();
                                                if (cause instanceof SnapshotStore.SnapshotPinnedException) {
                                                        CommandFeedback.failure(source, mod,
                                                                        "foldbackup.command.delete.pinned", id, id);
                                                        return;
                                                }
                                                FoldBackupMod.LOG.error("Failed to delete snapshot {}", id, cause);
                                                CommandFeedback.failure(source, mod, "foldbackup.command.delete.failed",
                                                                id);
                                        } else if (gc != null && !gc.isClean()) {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                "foldbackup.command.delete.cleanup_incomplete", id),
                                                                true);
                                        } else {
                                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                                "foldbackup.command.delete.success", id), true);
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

                future.whenComplete((result, throwable) -> mod.getServer().execute(() -> {
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
                        List<BackupExporter.SnapshotInfo> snapshots = new BackupExporter(store)
                                        .listSnapshotsWithCumulativeSpace();
                        if (snapshots.isEmpty()) {
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.empty"), false);
                                return 0;
                        }

                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.title",
                                        snapshots.size()), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.header"),
                                        false);
                        for (BackupExporter.SnapshotInfo snapshot : snapshots) {
                                String rowId = snapshot.pinned ? snapshot.id + " [PINNED]" : snapshot.id;
                                String rowRegions = String.valueOf(snapshot.regions);
                                String rowFiles = String.valueOf(snapshot.files);
                                String rowCompressed = FormatUtil.formatSize(snapshot.compressedBytes);
                                String rowUncompressed = FormatUtil.formatSize(snapshot.uncompressedBytes);
                                String rowCumulativeSpace = snapshot.pinned
                                                ? "-"
                                                : FormatUtil.formatSize(snapshot.cumulativeSpaceBytes);
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.row",
                                                rowId, snapshot.date, rowRegions, rowFiles,
                                                rowCompressed, rowUncompressed, rowCumulativeSpace), false);
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
                                        summary.getTotalCompressedBytes() / (1024 * 1024),
                                        summary.getTotalUncompressedBytes() / (1024 * 1024)), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                        "foldbackup.command.info.new_data",
                                        summary.getNewBlobBytes() / (1024 * 1024)), false);
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
