package io.github.truscdo.foldbackup.mod.command;

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

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                                                                                                                .getString(ctx, "id"))))));
        }

        private static int deleteSnapshot(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod, String id) {
                CommandSourceStack source = ctx.getSource();
                SnapshotStore store = BackendGuard.requireSnapshotStore(source, mod);
                if (store == null || !BackendGuard.requireValidSnapshotId(source, mod, id)) {
                        return 0;
                }
                Long epochSeconds = BackendGuard.resolveEpoch(source, mod, store, id);
                if (epochSeconds == null) {
                        return 0;
                }
                source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.delete.started", id),
                                true);
                mod.deleteSnapshotAsync(epochSeconds)
                                .whenComplete((ignored, exception) -> mod.getServer().execute(() -> {
                                        if (exception != null) {
                                                Throwable cause = exception.getCause() == null ? exception
                                                                : exception.getCause();
                                                FoldBackupMod.LOG.error("Failed to delete snapshot {}", id, cause);
                                                CommandFeedback.failure(source, mod, "foldbackup.command.delete.failed",
                                                                id);
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
                if (executor.isBackupRunning()) {
                        CommandFeedback.failure(source, mod, "foldbackup.command.backup.already_running");
                        return 0;
                }

                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                force ? "foldbackup.command.backup.start_forced" : "foldbackup.command.backup.start"),
                                false);
                if (source.getEntity() instanceof ServerPlayer player) {
                        mod.getProgressNotifier().subscribe(player);
                }

                executor.triggerBackup(force)
                                .whenComplete((result, throwable) -> mod.getServer().execute(() -> {
                                        if (source.getEntity() instanceof ServerPlayer player) {
                                                mod.getProgressNotifier().unsubscribe(player.getUUID());
                                        }
                                        if (throwable != null) {
                                                FoldBackupMod.LOG.error("Backup command failed", throwable);
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
                                }));
                return Command.SINGLE_SUCCESS;
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
                        List<SnapshotManifest> snapshots = store.loadAllSnapshots();
                        if (snapshots.isEmpty()) {
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.empty"), false);
                                return 0;
                        }

                        Map<Long, Long> cumulativeSpaceByEpoch = new HashMap<>();
                        for (SnapshotReclamationEstimator.DeletionEstimate estimate : SnapshotReclamationEstimator
                                        .evaluateDeletionChain(snapshots)) {
                                cumulativeSpaceByEpoch.put(estimate.epochSeconds(),
                                                estimate.cumulativeReclaimedBytes());
                        }

                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.title",
                                        snapshots.size()), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.list.header"),
                                        false);
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        for (SnapshotManifest snapshot : snapshots) {
                                String date = dateFormat
                                                .format(Date.from(Instant.ofEpochSecond(snapshot.getEpochSeconds())));
                                String regions = "-";
                                String files = "-";
                                String compressed = "-";
                                String uncompressed = "-";
                                String cumulativeSpace = FormatUtil.formatSize(
                                                cumulativeSpaceByEpoch.getOrDefault(snapshot.getEpochSeconds(), 0L));
                                if (snapshot.getSummary() != null) {
                                        var summary = snapshot.getSummary();
                                        regions = String.valueOf(summary.getRegionsTotal());
                                        files = String.valueOf(summary.getExtraFilesTotal());
                                        compressed = FormatUtil.formatSize(summary.getTotalCompressedBytes());
                                        uncompressed = FormatUtil.formatSize(summary.getTotalUncompressedBytes());
                                }
                                String rowRegions = regions;
                                String rowFiles = files;
                                String rowCompressed = compressed;
                                String rowUncompressed = uncompressed;
                                String rowCumulativeSpace = cumulativeSpace;
                                source.sendSuccess(() -> CommandFeedback.text(source, mod,
                                                "foldbackup.command.list.row",
                                                snapshot.getSnapshotId(), date, rowRegions, rowFiles,
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
                if (store == null || !BackendGuard.requireValidSnapshotId(source, mod, id)) {
                        return 0;
                }

                try {
                        Long epochSeconds = store.resolveEpoch(id);
                        SnapshotManifest snapshot = store.loadSnapshot(epochSeconds);
                        if (snapshot == null) {
                                CommandFeedback.failure(source, mod, "foldbackup.command.info.not_found", id);
                                return 0;
                        }

                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        String date = dateFormat.format(Date.from(Instant.ofEpochSecond(snapshot.getEpochSeconds())));
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.info.title",
                                        snapshot.getSnapshotId()), false);
                        source.sendSuccess(() -> CommandFeedback.text(source, mod, "foldbackup.command.info.time",
                                        date), false);
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
                } catch (Exception exception) {
                        FoldBackupMod.LOG.error("Failed to read snapshot info for {}", id, exception);
                        CommandFeedback.failure(source, mod, "foldbackup.command.info.failed", id);
                }
                return Command.SINGLE_SUCCESS;
        }
}
