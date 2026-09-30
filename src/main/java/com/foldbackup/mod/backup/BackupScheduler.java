package com.foldbackup.mod.backup;

import com.foldbackup.core.LogUtil;
import com.foldbackup.mod.config.FoldBackupConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 在线时间累加器和备份触发调度器。
 * <p>
 * 每 10 秒执行一次。仅当至少有一名玩家在线时计算时间。
 * 将 {@code accruedMs} 和 {@code lastBackupMs} 持久化到
 * {@code timer-state.properties}，
 * 以便服务器重启不会重置累积的在线游戏时间。
 */
public class BackupScheduler {

    private static final LogUtil LOG = LogUtil.getLogger(BackupScheduler.class);
    private static final long TICK_INTERVAL = 200; // 每 200 刻检查一次
    private static final long TICK_INTERVAL_MS = 10_000; // 200 刻 × 50 毫秒

    private final FoldBackupConfig config;
    private Path timerStatePath;

    private long lastBackupTime = System.currentTimeMillis();
    private long accumulatedOnlineMillis = 0;
    private long tickCounter = 0;

    public BackupScheduler(FoldBackupConfig config) {
        this.config = config;
    }

    // --- 初始化 ---

    /**
     * 设置备份根路径并恢复任何持久化的计时器状态。
     * 必须在后端初始化期间调用一次。
     */
    public void init(Path backupRoot) {
        this.timerStatePath = backupRoot.resolve("timer-state.properties");
        loadState();
    }

    // --- 刻更新 ---

    /**
     * 推进刻计数器并累积在线时间。
     * 在每次服务器刻更新事件中调用此方法。
     *
     * @param hasPlayers 当至少有一名玩家在线时为 {@code true}
     * @return 当累积了足够的在线时间以触发备份时返回 {@code true}
     */
    public boolean shouldTrigger(boolean hasPlayers) {
        tickCounter++;
        if (tickCounter % TICK_INTERVAL != 0)
            return false;

        // 墙钟最小间隔（防止快速手动备份的安全网）
        if (System.currentTimeMillis() - lastBackupTime < config.getMinIntervalMinutes() * 60000L) {
            return false;
        }

        // 仅当有玩家在线时才累积
        if (hasPlayers) {
            accumulatedOnlineMillis += TICK_INTERVAL_MS;
        }

        return accumulatedOnlineMillis >= config.getIntervalMinutes() * 60000L;
    }

    /**
     * 在成功备份后重置计时器并持久化干净状态。
     */
    public void markBackupComplete() {
        lastBackupTime = System.currentTimeMillis();
        accumulatedOnlineMillis = 0;
        saveState();
    }

    // --- 查询 ---

    /** 上次成功备份的墙钟时间戳（毫秒）。 */
    public long getLastBackupTime() {
        return lastBackupTime;
    }

    /** 累积的在线时间（整分钟，用于日志/状态显示）。 */
    public long getAccumulatedOnlineMinutes() {
        return accumulatedOnlineMillis / 60000;
    }

    // --- 持久化 ---

    /**
     * 将当前计时器状态持久化到磁盘。
     * 在每次成功备份和服务器关闭时调用。
     */
    public void saveState() {
        if (timerStatePath == null)
            return;
        try {
            Properties props = new Properties();
            props.setProperty("accruedMs", Long.toString(accumulatedOnlineMillis));
            props.setProperty("lastBackupMs", Long.toString(lastBackupTime));
            try (OutputStream out = Files.newOutputStream(timerStatePath)) {
                props.store(out, "foldbackup timer state");
            }
        } catch (IOException e) {
            LOG.warn("Failed to save timer state", e);
        }
    }

    /**
     * 恢复先前持久化的计时器状态。
     * 如果文件不存在或损坏，则静默回退到默认值。
     */
    private void loadState() {
        if (timerStatePath == null || !Files.exists(timerStatePath))
            return;
        try (InputStream in = Files.newInputStream(timerStatePath)) {
            Properties props = new Properties();
            props.load(in);
            accumulatedOnlineMillis = Long.parseLong(props.getProperty("accruedMs", "0"));
            lastBackupTime = Long.parseLong(props.getProperty("lastBackupMs", "0"));
            LOG.info("Restored timer state: {} min online accrued, last backup {}",
                    accumulatedOnlineMillis / 60000, lastBackupTime);
        } catch (IOException | NumberFormatException e) {
            LOG.warn("Failed to load timer state, starting fresh", e);
        }
    }
}
