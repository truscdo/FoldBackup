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
 * 并对任务进度日志消息进行限速，以免刷屏服务器日志。
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
        BackupScheduler scheduler = mod.getScheduler();
        if (scheduler != null && scheduler.shouldTrigger(
                mod.getServer() != null && mod.getServer().getPlayerCount() > 0)) {
            LOG.info("Scheduled backup triggered ({} min of online time accumulated)",
                    mod.getConfig().getIntervalMinutes());
            mod.getExecutor().triggerBackup(false);
        }

        // 限速的任务进度日志
        logTaskProgress();

        // 推送进度给正在查看备份的玩家
        mod.getProgressNotifier().pushProgress(mod.getTaskRegistry().getCurrentActive(), mod.getConfig());
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
