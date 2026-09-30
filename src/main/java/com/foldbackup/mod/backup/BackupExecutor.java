package com.foldbackup.mod.backup;

import com.foldbackup.core.BackupResult;
import com.foldbackup.core.LogUtil;
import com.foldbackup.core.backup.BackupCoordinator;
import com.foldbackup.core.backup.BackupStep;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskStatus;
import com.foldbackup.core.task.TaskType;
import com.foldbackup.mod.config.FoldBackupConfig;
import com.foldbackup.mod.i18n.BackupStepFormat;
import com.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行备份周期并防护并发执行。
 * <p>
 * 每个备份路径都通过一个单一关卡，该关卡强制执行最小墙钟间隔和"已在运行"检查。
 * 成功时重置 {@link BackupScheduler} 状态。
 * <p>
 * 进度通过 {@link TaskStatus} 报告，由调用方提供（对于简单的重载版本会自动创建）。
 */
public class BackupExecutor {

    private static final LogUtil LOG = LogUtil.getLogger(BackupExecutor.class);

    private final java.util.concurrent.Executor mainThread;
    private final java.util.function.Consumer<TaskStatus> failureNotifier;
    private final WorldAccessor worldAccessor;
    private final BackupCoordinator coordinator;
    private final FoldBackupConfig config;
    private final BackupScheduler scheduler;
    private final ExecutorService backupExecutor;
    private final TaskRegistry taskRegistry;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);

    public BackupExecutor(MinecraftServer server, WorldAccessor worldAccessor,
            BackupCoordinator coordinator, FoldBackupConfig config,
            BackupScheduler scheduler, ExecutorService backupExecutor,
            TaskRegistry taskRegistry) {
        this(server::execute, status -> server.getPlayerList().getPlayers()
                .forEach(player -> player.sendSystemMessage(
                        status.stepProgress() instanceof BackupStep.CleanupFailed failed
                                ? FoldBackupTranslations.componentForPlayer(player, config,
                                        "foldbackup.progress.cleanup_failed", failed.snapshotId())
                                : FoldBackupTranslations.componentForPlayer(player, config, "foldbackup.backup.failure"))),
                worldAccessor, coordinator, config, scheduler, backupExecutor, taskRegistry);
    }

    BackupExecutor(java.util.concurrent.Executor mainThread, Runnable failureNotifier,
            WorldAccessor worldAccessor, BackupCoordinator coordinator, FoldBackupConfig config,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry) {
        this(mainThread, status -> failureNotifier.run(), worldAccessor, coordinator, config,
                scheduler, backupExecutor, taskRegistry);
    }

    private BackupExecutor(java.util.concurrent.Executor mainThread,
            java.util.function.Consumer<TaskStatus> failureNotifier,
            WorldAccessor worldAccessor, BackupCoordinator coordinator, FoldBackupConfig config,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry) {
        this.mainThread = mainThread;
        this.failureNotifier = failureNotifier;
        this.worldAccessor = worldAccessor;
        this.coordinator = coordinator;
        this.config = config;
        this.scheduler = scheduler;
        this.backupExecutor = backupExecutor;
        this.taskRegistry = taskRegistry;
    }

    // --- 防护 ---

    private boolean minIntervalOk(boolean force) {
        if (force)
            return true;
        long elapsed = System.currentTimeMillis() - scheduler.getLastBackupTime();
        return elapsed >= config.getMinIntervalMinutes() * 60000L;
    }

    private BackupResult acquireRunSlot() {
        if (worldAccessor == null || coordinator == null) {
            return BackupResult.NOT_INITIALIZED;
        }
        if (backupRunning.getAndSet(true)) {
            return BackupResult.ALREADY_RUNNING;
        }
        return null; // slot acquired
    }

    // --- 异步路径 ---

    /**
     * 触发备份，自动创建新的 {@link TaskStatus}。
     */
    public CompletableFuture<BackupResult> triggerBackup(boolean force) {
        if (!minIntervalOk(force)) {
            LOG.info("Backup skipped — minimum interval of {} min not elapsed",
                    config.getMinIntervalMinutes());
            return CompletableFuture.completedFuture(BackupResult.TOO_SOON);
        }
        BackupResult slot = acquireRunSlot();
        if (slot != null)
            return CompletableFuture.completedFuture(slot);

        TaskStatus status = taskRegistry.startTask(TaskType.BACKUP);
        return executeBackupAsync(status);
    }

    /**
     * 使用外部提供的 {@link TaskStatus} 触发备份。
     * 调用方负责在调用此方法前检查防护条件（{@link #isBackupRunning()}）。
     */
    public CompletableFuture<BackupResult> triggerBackup(boolean force, TaskStatus status) {
        status.setRunning();
        return executeBackupAsync(status);
    }

    private CompletableFuture<BackupResult> executeBackupAsync(TaskStatus status) {
        CompletableFuture<BackupResult> result = new CompletableFuture<>();
        mainThread.execute(() -> {
            try {
                // 先强制刷盘，再禁用保存：noSave=true 的 level 会被
                // saveAllChunks 跳过，顺序反了会让强制保存变成空操作。
                worldAccessor.saveAll(true);
                worldAccessor.disableSaving();
                CompletableFuture.supplyAsync(() -> {
                    try {
                        coordinator.runBackup(status);
                        return BackupResult.SUCCESS;
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, backupExecutor).whenComplete((ok, ex) -> mainThread.execute(() ->
                        finishBackup(result, status, ok, ex)));
            } catch (Exception e) {
                finishBackup(result, status, BackupResult.FAILED, e);
            }
        });
        return result;
    }

    /** Runs on the server thread, including when worker submission fails synchronously. */
    private void finishBackup(CompletableFuture<BackupResult> result, TaskStatus status,
            BackupResult ok, Throwable ex) {
        BackupResult outcome = BackupResult.FAILED;
        try {
            worldAccessor.enableSaving();
            if (ex != null) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof SnapshotStore.CommittedCleanupException)
                    scheduler.markBackupComplete();
                LOG.error("Backup failed", cause);
                taskRegistry.fail(status, "Backup failed: " + cause.getMessage());
                notifyPlayersOfBackupFailure(status);
            } else if (BackupResult.SUCCESS.equals(ok)) {
                scheduler.markBackupComplete();
                LOG.info("[{}] {}", status.taskId(), BackupStepFormat.format(status.stepProgress()));
                taskRegistry.complete(status);
                outcome = BackupResult.SUCCESS;
            } else {
                taskRegistry.fail(status, "Backup failed with unexpected result");
                notifyPlayersOfBackupFailure(status);
            }
        } catch (Exception e) {
            LOG.error("Failed to restore world saving", e);
            taskRegistry.fail(status, "Failed to restore world saving: " + e.getMessage());
            notifyPlayersOfBackupFailure(status);
        } finally {
            backupRunning.set(false);
            result.complete(outcome);
        }
    }

    // --- 阻塞路径 ---

    /**
     * 在调用线程上同步运行备份。
     * 自动创建新的 {@link TaskStatus}。
     */
    public BackupResult triggerBackupBlocking(boolean force) {
        if (!minIntervalOk(force)) {
            LOG.info("Backup skipped — minimum interval of {} min not elapsed",
                    config.getMinIntervalMinutes());
            return BackupResult.TOO_SOON;
        }
        BackupResult slot = acquireRunSlot();
        if (slot != null)
            return slot;

        TaskStatus status = taskRegistry.startTask(TaskType.BACKUP).setRunning();

        try {
            // 同上：先刷盘，再禁用保存。
            worldAccessor.saveAll(true);
            worldAccessor.disableSaving();
            coordinator.runBackup(status);
            scheduler.markBackupComplete();
            LOG.info("[{}] {}", status.taskId(), BackupStepFormat.format(status.stepProgress()));
            taskRegistry.complete(status);
            return BackupResult.SUCCESS;
        } catch (Exception e) {
            if (e instanceof SnapshotStore.CommittedCleanupException)
                scheduler.markBackupComplete();
            LOG.error("Backup failed", e);
            taskRegistry.fail(status, "Backup failed: " + e.getMessage());
            notifyPlayersOfBackupFailure(status);
            return BackupResult.FAILED;
        } finally {
            try {
                worldAccessor.enableSaving();
            } catch (Exception e) {
                LOG.error("Failed to restore world saving", e);
            }
            backupRunning.set(false);
        }
    }

    // --- 查询 ---

    public boolean isBackupRunning() {
        return backupRunning.get();
    }

    // --- 内部方法 ---

    /** Notify all online players when a backup run fails, including scheduled backups. */
    private void notifyPlayersOfBackupFailure(TaskStatus status) {
        try {
            mainThread.execute(() -> failureNotifier.accept(status));
        } catch (RuntimeException e) {
            LOG.warn("Unable to notify players of backup failure", e);
        }
    }
}
