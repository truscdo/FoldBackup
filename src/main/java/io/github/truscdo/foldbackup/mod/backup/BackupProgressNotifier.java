package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.mod.i18n.BackupStepFormat;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 管理备份进度订阅和推送；所有方法均由服务器主线程调用。 */
public final class BackupProgressNotifier {

    private final Map<UUID, ServerPlayer> watchers = new HashMap<>();
    private long lastProgressPush;

    public void subscribe(ServerPlayer player) {
        watchers.put(player.getUUID(), player);
    }

    public void unsubscribe(UUID playerId) {
        watchers.remove(playerId);
    }

    public void clear() {
        watchers.clear();
        lastProgressPush = 0;
    }

    /**
     * 向通过 /foldbackup backup 订阅的玩家推送进度更新。
     * 限速为 1 秒，避免聊天刷屏。
     */
    public void pushProgress(TaskStatus active, FoldBackupConfig config) {
        if (watchers.isEmpty())
            return;

        long now = System.currentTimeMillis();
        if (now - lastProgressPush < 1000L)
            return;
        lastProgressPush = now;

        if (active == null)
            return;

        // 在迭代过程中移除已断线的玩家
        watchers.values()
                .removeIf(p -> !p.isAlive());

        for (var player : watchers.values()) {
            String language = FoldBackupTranslations.resolveLanguage(player, config);
            String progress = BackupStepFormat.formatLocalized(active.stepProgress(), language);
            String key = active.progressPercent() >= 0
                    ? "foldbackup.progress.push_percent"
                    : "foldbackup.progress.push";
            if (active.progressPercent() >= 0) {
                player.sendSystemMessage(FoldBackupTranslations.componentForLanguage(language, key,
                        active.progressPercent(), progress));
            } else {
                player.sendSystemMessage(FoldBackupTranslations.componentForLanguage(language, key, progress));
            }
        }
    }
}
