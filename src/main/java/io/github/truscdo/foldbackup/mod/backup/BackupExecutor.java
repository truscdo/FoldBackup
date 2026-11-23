package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.BackupResult;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.BackupStep;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.core.task.TaskType;
import io.github.truscdo.foldbackup.mod.command.CommandPermissions;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.i18n.BackupStepFormat;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 执行备份周期并防护并发执行。
 * <p>
 * 每个备份路径都通过一个单一关卡，该关卡强制执行最小墙钟间隔和"已在运行"检查。
 * 成功时重置 {@link BackupScheduler} 状态。
 * <p>
 * 备份开始前关闭世界自动保存，扫描完成后立即恢复；扫描期间由
 * {@link SavePauseLease} 记录进展。服务器线程每刻调用 {@link #checkSavePause()}，
 * 超过运行开始时取好的停滞阈值没有进展，就中止本次备份并强制恢复保存，
 * 避免自动保存无限期关闭。
 * <p>
 * 进度通过 {@link TaskStatus} 报告，由各入口方法创建并登记到 {@link TaskRegistry}。
 */
public class BackupExecutor {

    private static final LogUtil LOG = LogUtil.getLogger(BackupExecutor.class);

    /** 一次备份运行的全部状态；用 CAS 决定由哪一方（正常完成或看门狗）收尾。 */
    private static final class Run {
        private final TaskStatus status;
        private final CompletableFuture<BackupResult> future = new CompletableFuture<>();
        private final SavePauseLease pause = new SavePauseLease();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Thread worker;
        private volatile BackupStep lastStep;
        /** 本次运行的停滞阈值；暂停开始时取一次快照，只在服务器线程读写，0 表示不检查。 */
        private long stallMillis;

        Run(TaskStatus status) {
            this.status = status;
        }
    }

    private final java.util.concurrent.Executor mainThread;
    private final PlayerWarning playerWarning;
    private final PlayerWarning adminWarning;
    private final WorldAccessor worldAccessor;
    /** 仓库延迟就绪时由 {@link #attachCoordinator} 补挂；挂上后不再变化。 */
    private volatile BackupCoordinator coordinator;
    private final BackupScheduler scheduler;
    private final ExecutorService backupExecutor;
    private final TaskRegistry taskRegistry;
    /** 停滞阈值的来源；每次运行开始时读取一次，测试可注入固定值。 */
    private final LongSupplier savePauseTimeoutMillis;
    private final LongSupplier clock;
    private final AtomicReference<Run> currentRun = new AtomicReference<>();

    private static final PlayerWarning NOOP_WARNING = (key, args) -> {};

    public BackupExecutor(MinecraftServer server, WorldAccessor worldAccessor,
            BackupCoordinator coordinator, FoldBackupConfig config,
            BackupScheduler scheduler, ExecutorService backupExecutor,
            TaskRegistry taskRegistry) {
        this(server::execute, playersOf(server, config), adminsOf(server, config), worldAccessor, coordinator,
                scheduler, backupExecutor, taskRegistry,
                () -> Math.max(0, config.getSavePauseStallSeconds()) * 1000L, System::currentTimeMillis);
    }

    /** 测试用：不启用看门狗。 */
    BackupExecutor(java.util.concurrent.Executor mainThread, PlayerWarning playerWarning,
            WorldAccessor worldAccessor, BackupCoordinator coordinator,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry) {
        this(mainThread, playerWarning, NOOP_WARNING, worldAccessor, coordinator, scheduler, backupExecutor,
                taskRegistry, () -> 0L, System::currentTimeMillis);
    }

    /** 测试用：不启用看门狗，可独立注入管理员告警回调。 */
    BackupExecutor(java.util.concurrent.Executor mainThread, PlayerWarning playerWarning,
            PlayerWarning adminWarning, WorldAccessor worldAccessor, BackupCoordinator coordinator,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry) {
        this(mainThread, playerWarning, adminWarning, worldAccessor, coordinator, scheduler, backupExecutor,
                taskRegistry, () -> 0L, System::currentTimeMillis);
    }

    BackupExecutor(java.util.concurrent.Executor mainThread, PlayerWarning playerWarning,
            WorldAccessor worldAccessor, BackupCoordinator coordinator,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry,
            LongSupplier savePauseTimeoutMillis) {
        this(mainThread, playerWarning, NOOP_WARNING, worldAccessor, coordinator, scheduler, backupExecutor,
                taskRegistry, savePauseTimeoutMillis, System::currentTimeMillis);
    }

    BackupExecutor(java.util.concurrent.Executor mainThread, PlayerWarning playerWarning,
            WorldAccessor worldAccessor, BackupCoordinator coordinator,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry,
            LongSupplier savePauseTimeoutMillis, LongSupplier clock) {
        this(mainThread, playerWarning, NOOP_WARNING, worldAccessor, coordinator, scheduler, backupExecutor,
                taskRegistry, savePauseTimeoutMillis, clock);
    }

    BackupExecutor(java.util.concurrent.Executor mainThread, PlayerWarning playerWarning,
            PlayerWarning adminWarning, WorldAccessor worldAccessor, BackupCoordinator coordinator,
            BackupScheduler scheduler, ExecutorService backupExecutor, TaskRegistry taskRegistry,
            LongSupplier savePauseTimeoutMillis, LongSupplier clock) {
        this.mainThread = mainThread;
        this.playerWarning = playerWarning;
        this.adminWarning = adminWarning == null ? NOOP_WARNING : adminWarning;
        this.worldAccessor = worldAccessor;
        this.coordinator = coordinator;
        this.scheduler = scheduler;
        this.backupExecutor = backupExecutor;
        this.taskRegistry = taskRegistry;
        this.savePauseTimeoutMillis = savePauseTimeoutMillis;
        this.clock = clock;
    }

    private static PlayerWarning playersOf(MinecraftServer server, FoldBackupConfig config) {
        return (key, args) -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(FoldBackupTranslations.componentForPlayer(player, config, key, args));
            }
        };
    }

    private static PlayerWarning adminsOf(MinecraftServer server, FoldBackupConfig config) {
        return (key, args) -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (CommandPermissions.canManageBackups(player)) {
                    player.sendSystemMessage(FoldBackupTranslations.componentForPlayer(player, config, key, args));
                }
            }
        };
    }

    /**
     * 仓库在运行期才打开成功时补挂协调器。
     * <p>
     * 只由 {@code WorldRuntime} 在后台重试成功后调用一次；调用后备份入口即可正常工作。
     */
    public void attachCoordinator(BackupCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 前置守卫与运行位抢占的结果：{@code run != null} 表示占槽成功，否则 {@code rejected} 为拒绝原因。 */
    private record AcquireOutcome(Run run, BackupResult rejected) {
        static AcquireOutcome accepted(Run run) {
            return new AcquireOutcome(run, null);
        }

        static AcquireOutcome rejected(BackupResult reason) {
            return new AcquireOutcome(null, reason);
        }
    }

    // --- 防护 ---

    private boolean minIntervalOk(boolean force) {
        if (force)
            return true;
        return scheduler == null || scheduler.isMinIntervalElapsed(clock.getAsLong());
    }

    /**
     * 统一前置守卫与运行位抢占：依次检查最小间隔、组件就绪与并发运行位。
     * 先做 {@code currentRun.get() != null} 快速预检，避免在常规并发冲突时创建多余的失败 {@link TaskStatus}；
     * 仅在预检通过后创建任务并用 CAS 抢占唯一运行位。
     *
     * @param force          是否跳过最小间隔检查
     * @param runningOnStart 是否在抢占前将任务直接置为 RUNNING（阻塞路径使用）
     */
    private AcquireOutcome tryAcquireRun(boolean force, boolean runningOnStart) {
        if (!minIntervalOk(force)) {
            LOG.info("Backup skipped — minimum interval of {} min not elapsed",
                    scheduler.getMinIntervalMinutes());
            return AcquireOutcome.rejected(BackupResult.TOO_SOON);
        }
        if (worldAccessor == null || coordinator == null) {
            return AcquireOutcome.rejected(BackupResult.NOT_INITIALIZED);
        }
        if (currentRun.get() != null) {
            return AcquireOutcome.rejected(BackupResult.ALREADY_RUNNING);
        }

        TaskStatus status = taskRegistry.startTask(TaskType.BACKUP);
        if (runningOnStart) {
            status.setRunning();
        }
        Run run = claimRun(status);
        if (run == null) {
            return AcquireOutcome.rejected(BackupResult.ALREADY_RUNNING);
        }
        return AcquireOutcome.accepted(run);
    }

    /**
     * 抢占唯一运行位；抢占失败时收尾刚创建的任务，避免它一直挂在活动列表里。
     *
     * @return 抢到的运行；运行位已被占用时返回 {@code null}
     */
    private Run claimRun(TaskStatus status) {
        Run run = new Run(status);
        if (currentRun.compareAndSet(null, run))
            return run;
        run.finished.set(true);
        taskRegistry.fail(status, "Another backup is already running");
        return null;
    }

    // --- 异步路径 ---

    /**
     * 触发备份，自动创建新的 {@link TaskStatus}。
     */
    public CompletableFuture<BackupResult> triggerBackup(boolean force) {
        return triggerBackup(force, null);
    }

    /**
     * 触发备份；仅在通过前置校验并成功占用运行位、准备启动任务时同步调用 {@code onAccepted}。
     */
    public CompletableFuture<BackupResult> triggerBackup(boolean force, Runnable onAccepted) {
        AcquireOutcome outcome = tryAcquireRun(force, false);
        if (outcome.run() == null)
            return CompletableFuture.completedFuture(outcome.rejected());
        if (onAccepted != null)
            onAccepted.run();
        startAsync(outcome.run());
        return outcome.run().future;
    }

    private void startAsync(Run run) {
        try {
            mainThread.execute(() -> scheduleRun(run));
        } catch (RuntimeException e) {
            // 主线程已拒绝任务：此时尚未暂停保存，只需归还运行位。
            finishBackup(run, BackupResult.FAILED, e);
        }
    }

    /** 服务器线程：强制刷盘并暂停保存，然后把备份交给备份线程。 */
    private void scheduleRun(Run run) {
        try {
            // 阈值在运行开始时取一次快照：整次运行共用一个判定标准，
            // 也避免每刻在 tick 路径上读配置。放在暂停之前读，
            // 读失败时世界保存状态还没有被改动。
            run.stallMillis = savePauseTimeoutMillis.getAsLong();
            prepareWorldForBackup();
            run.pause.begin(System.currentTimeMillis());
            CompletableFuture.supplyAsync(() -> runBackupOnWorker(run), backupExecutor)
                    .whenComplete((ok, ex) -> mainThread.execute(() -> finishBackup(run, ok, ex)));
        } catch (Exception e) {
            finishBackup(run, BackupResult.FAILED, e);
        }
    }

    /** 先强制刷盘再禁用保存：noSave=true 的维度会被 saveAllChunks 跳过，顺序不可颠倒。 */
    private void prepareWorldForBackup() {
        worldAccessor.saveAll(true);
        worldAccessor.disableSaving();
    }

    /** 备份线程：执行扫描与提交，进度回送给 {@link #onStep}。 */
    private BackupResult runBackupOnWorker(Run run) {
        run.worker = Thread.currentThread();
        if (run.finished.get()) // 排队期间已被看门狗中止
            return BackupResult.FAILED;
        try {
            coordinator.runBackup(run.status, step -> onStep(run, step));
            return BackupResult.SUCCESS;
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /** 由备份线程调用：续期暂停租约，并在世界读取阶段结束时提前恢复保存。 */
    private void onStep(Run run, BackupStep step) {
        run.lastStep = step;
        run.pause.activity(System.currentTimeMillis());
        if (!isScanFinished(step))
            return;
        // 扫描已经结束，后续的清单构建、索引提交和 blob 回收都不再读世界目录。
        run.pause.end();
        restoreSavingAfterScanOnMainThread(run);
    }

    /** 世界扫描的最后一步；之后的步骤都不再读世界目录。 */
    private static boolean isScanFinished(BackupStep step) {
        return step instanceof BackupStep.PreparingSnapshot;
    }

    /**
     * 把恢复保存排到服务器线程。
     * 已被中止的运行由中止路径负责恢复，这里跳过，避免踩到下一次备份。
     */
    private void restoreSavingAfterScanOnMainThread(Run run) {
        mainThread.execute(() -> {
            if (!run.finished.get())
                restoreSavingAfterScan();
        });
    }

    /** 世界扫描结束时恢复保存；异步路径在服务器线程、阻塞路径在调用线程上执行。 */
    private void restoreSavingAfterScan() {
        if (restoreSaving("after the world scan"))
            LOG.info("World scan finished; automatic saving restored");
    }

    /**
     * 恢复世界保存；失败只记录日志，不打断调用方的收尾流程。
     *
     * @param failureContext 失败日志的上下文短语，例如 {@code "after the world scan"}
     * @return 是否恢复成功
     */
    private boolean restoreSaving(String failureContext) {
        try {
            worldAccessor.enableSaving();
            return true;
        } catch (Exception e) {
            LOG.error("Failed to restore world saving " + failureContext, e);
            return false;
        }
    }

    /** 收尾：恢复保存、登记任务结果、更新调度计时并完成 future；正常情况在服务器线程上执行，主线程拒绝任务时可能在调用线程上。 */
    private void finishBackup(Run run, BackupResult ok, Throwable ex) {
        if (!run.finished.compareAndSet(false, true)) {
            LOG.warn("Ignoring the completion of a backup run that was already aborted");
            return;
        }
        run.pause.end();
        boolean savingRestored = restoreSaving("when finishing backup");
        BackupResult outcome = BackupResult.FAILED;
        Throwable failureCause = unwrapCompletion(ex);
        try {
            if (failureCause != null) {
                LOG.error("Backup failed", failureCause);
                taskRegistry.fail(run.status, "Backup failed: " + failureCause.getMessage());
                notifyPlayersOfBackupFailure(failureCause);
            } else if (!savingRestored) {
                taskRegistry.fail(run.status, "Failed to restore world saving");
                notifyPlayersOfBackupFailure();
            } else if (BackupResult.SUCCESS.equals(ok)) {
                LOG.info("[{}] {}", run.status.taskId(), BackupStepFormat.format(run.status.stepProgress()));
                taskRegistry.complete(run.status);
                notifyAdminsIfCapacityOverLimit(run);
                outcome = BackupResult.SUCCESS;
            } else {
                taskRegistry.fail(run.status, "Backup failed with unexpected result");
                notifyPlayersOfBackupFailure();
            }
        } finally {
            try {
                updateSchedulerAfterRun(outcome, failureCause);
            } finally {
                currentRun.compareAndSet(run, null);
                run.future.complete(outcome);
            }
        }
    }

    private static Throwable unwrapCompletion(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    private void updateSchedulerAfterRun(BackupResult outcome, Throwable failureCause) {
        if (scheduler == null)
            return;
        if (outcome == BackupResult.SUCCESS || failureCause instanceof SnapshotStore.CommittedCleanupException) {
            scheduler.markBackupComplete();
        } else {
            scheduler.markAttemptFailed();
        }
    }

    // --- 阻塞路径 ---

    /**
     * 在调用线程上同步运行备份。
     * 自动创建新的 {@link TaskStatus}。
     */
    public BackupResult triggerBackupBlocking(boolean force) {
        AcquireOutcome outcome = tryAcquireRun(force, true);
        if (outcome.run() == null)
            return outcome.rejected();
        Run run = outcome.run();
        try {
            prepareWorldForBackup();
            coordinator.runBackup(run.status, step -> onBlockingStep(run, step));
            finishBackup(run, BackupResult.SUCCESS, null);
        } catch (Exception e) {
            finishBackup(run, BackupResult.FAILED, e);
        }
        return run.future.getNow(BackupResult.FAILED);
    }

    /** 阻塞路径在服务器线程上同步执行，可以直接在回调里恢复保存。 */
    private void onBlockingStep(Run run, BackupStep step) {
        run.lastStep = step;
        if (isScanFinished(step))
            restoreSavingAfterScan();
    }

    // --- 看门狗 ---

    /**
     * 服务器线程每刻调用：暂停保存期间长时间没有进展时中止本次备份。
     * 没有进行中的备份时只做一次 volatile 读；阈值用运行开始时的快照，
     * 这里不访问配置。
     */
    public void checkSavePause() {
        checkSavePause(System.currentTimeMillis());
    }

    void checkSavePause(long nowMillis) {
        Run run = currentRun.get();
        if (run == null || !run.pause.isActive())
            return;
        if (!run.pause.expired(nowMillis, run.stallMillis))
            return;
        abortStalled(run, nowMillis);
    }

    private void abortStalled(Run run, long nowMillis) {
        long stalledSeconds = Math.max(0L, nowMillis - run.pause.lastActivityMillis()) / 1000L;
        abortRun(run,
                () -> LOG.error("Backup made no progress for {}s while world saving was suspended; "
                        + "restoring saving and aborting the run (last step: {})",
                        stalledSeconds, run.lastStep == null ? "none" : run.lastStep.phase()),
                "Backup stalled: no progress for " + stalledSeconds + "s",
                "after aborting a stalled backup",
                () -> {
                    try {
                        playerWarning.send("foldbackup.backup.stalled", stalledSeconds);
                    } catch (RuntimeException e) {
                        LOG.warn("Unable to warn players about the stalled backup", e);
                    }
                },
                () -> {
                    if (scheduler != null)
                        scheduler.markAttemptFailed();
                });
    }

    /**
     * 关停兜底：中止仍在进行的异步备份，确保世界保存状态已恢复。
     * <p>
     * 服务器停止和世界运行时被替换都会走到这里；与看门狗不同，
     * 关停不触发调度退避，也不通知玩家。
     */
    public void cancelForShutdown() {
        Run run = currentRun.get();
        if (run == null)
            return;
        abortRun(run,
                () -> LOG.info("Cancelling the running backup because the world runtime is shutting down"),
                "Backup cancelled: world runtime shutting down",
                "while shutting down the world runtime",
                null,
                null);
    }

    private void abortRun(Run run, Runnable logAbort, String statusMessage, String releaseContext,
            Runnable beforeStopWorker, Runnable afterStopWorker) {
        if (!run.finished.compareAndSet(false, true))
            return;
        logAbort.run();
        // 先恢复保存，再处理任务与线程状态：数据安全优先于状态一致性。
        releaseRun(run, statusMessage, releaseContext);
        if (beforeStopWorker != null)
            beforeStopWorker.run();
        stopWorker(run);
        if (afterStopWorker != null)
            afterStopWorker.run();
        run.future.complete(BackupResult.FAILED);
    }

    /**
     * 释放运行位并恢复世界保存；看门狗中止与关停共用。
     * 先恢复保存，再处理任务状态：数据安全优先于状态一致性。
     */
    private void releaseRun(Run run, String failureMessage, String restoreContext) {
        run.pause.end();
        restoreSaving(restoreContext);
        currentRun.compareAndSet(run, null);
        taskRegistry.fail(run.status, failureMessage);
    }

    /** 中断备份线程，走 StreamIo/IoBudget 的既有取消协议。 */
    private static void stopWorker(Run run) {
        Thread worker = run.worker;
        if (worker != null)
            worker.interrupt();
    }

    // --- 查询 ---

    public boolean isBackupRunning() {
        return currentRun.get() != null;
    }

    // --- 内部方法 ---

    /**
     * 备份成功但因保留快照导致仓库超出容量上限时，向具备管理权限的在线玩家推送告警。
     * 必须在服务器线程上调用。
     */
    private void notifyAdminsIfCapacityOverLimit(Run run) {
        if (!(run.lastStep instanceof BackupStep.Completed completed)
                || completed.capacityOverLimitBytes() <= 0) {
            return;
        }
        try {
            adminWarning.send("foldbackup.backup.capacity_over_limit_pinned",
                    completed.snapshotId(),
                    FormatUtil.formatSize(completed.capacityOverLimitBytes()),
                    completed.pinnedSnapshots());
        } catch (RuntimeException e) {
            LOG.warn("Unable to warn administrators about repository capacity limit", e);
        }
    }

    /** 通知所有在线玩家备份失败；必须在服务器线程上调用。 */
    private void notifyPlayersOfBackupFailure() {
        notifyPlayersOfBackupFailure(null);
    }

    /**
     * 通知所有在线玩家备份失败。快照已提交、只有清理失败时发送对应的说明，
     * 避免让玩家误以为没有可用快照；必须在服务器线程上调用。
     *
     * @param cause 失败原因，可为 {@code null}
     */
    private void notifyPlayersOfBackupFailure(Throwable cause) {
        try {
            if (cause instanceof SnapshotStore.CommittedCleanupException cleanup)
                playerWarning.send("foldbackup.progress.cleanup_failed", cleanup.snapshotId());
            else
                playerWarning.send("foldbackup.backup.failure");
        } catch (RuntimeException e) {
            LOG.warn("Unable to notify players of backup failure", e);
        }
    }
}
