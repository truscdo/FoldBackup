package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.i18n.BackupStepFormat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 处理备份调度和进度日志的服务器刻更新事件。
 * <p>
 * 委托给 {@link BackupScheduler#shouldTrigger(boolean)} 决定何时启动计划备份，
 * 并对任务进度日志消息进行限速，以免刷屏服务器日志。同时驱动仓库的后台重试。
 */
public class TickHandler {

    private static final LogUtil LOG = LogUtil.getLogger(TickHandler.class);

    private final FoldBackupMod mod;
    private long lastTaskLogTime;
    private String lastTaskMessage = "";

    public TickHandler(FoldBackupMod mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // 仓库被占用时的后台重试：后端正常时只是一次 volatile 读。
        mod.tickBackendInit();

        // 保存暂停的看门狗：没有进行中的备份时只是一次 volatile 读。
        BackupExecutor executor = mod.getExecutor();
        if (executor != null) {
            executor.checkSavePause();
        }

        BackupScheduler scheduler = mod.getScheduler();
        if (scheduler != null && executor != null) {
            boolean hasPlayers = mod.getServer() != null && mod.getServer().getPlayerCount() > 0;
            tickScheduledBackup(scheduler, executor, hasPlayers, System.currentTimeMillis());
        }

        // 限速的任务进度日志
        logTaskProgress();

        // 推送进度给正在查看备份的玩家
        mod.getProgressNotifier().pushProgress(mod.getTaskRegistry().getCurrentActive(), mod.getConfig());
    }

    /**
     * 推进定时备份调度：仅在真正受理启动时记录触发日志，
     * 冷却跳过在同一等待周期内按边沿只记录一次，避免重复刷屏。
     */
    static void tickScheduledBackup(BackupScheduler scheduler, BackupExecutor executor,
            boolean hasPlayers, long nowMillis) {
        if (!scheduler.shouldTrigger(hasPlayers))
            return;
        if (executor.isBackupRunning())
            return;
        if (!scheduler.isMinIntervalElapsed(nowMillis)) {
            if (scheduler.consumeMinIntervalDeferralNotice()) {
                LOG.info("Scheduled backup deferred — {} min of online time accumulated, waiting for minimum interval of {} min",
                        scheduler.getIntervalMinutes(), scheduler.getMinIntervalMinutes());
            }
            return;
        }
        executor.triggerBackup(false, () -> LOG.info("Scheduled backup triggered ({} min of online time accumulated)",
                scheduler.getIntervalMinutes()));
    }

    /**
     * 检查是否有活动任务，并按配置中设置的间隔记录其当前消息。
     */
    private void logTaskProgress() {
        TaskStatus active = mod.getTaskRegistry().getCurrentActive();
        if (active == null)
            return;

        long intervalMs = mod.getConfig().getProgressLogIntervalSeconds() * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastTaskLogTime < intervalMs)
            return;
        lastTaskLogTime = now;

        String msg = BackupStepFormat.format(active.stepProgress());
        if (!msg.isEmpty() && !msg.equals(lastTaskMessage)) {
            lastTaskMessage = msg;
            LOG.info("[{}] {}", active.taskId(), msg);
        }
    }
}
