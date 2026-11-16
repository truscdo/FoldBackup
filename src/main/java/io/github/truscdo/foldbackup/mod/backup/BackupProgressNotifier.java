package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.logging.ProgressThrottler;
import io.github.truscdo.foldbackup.core.task.StepProgress;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.i18n.BackupStepFormat;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 管理备份进度订阅和推送；所有方法均由服务器主线程调用。 */
public final class BackupProgressNotifier {

    @FunctionalInterface
    interface WatcherSink {
        void accept(StepProgress step, FoldBackupConfig config);
    }

    private record Watcher(BooleanSupplier alive, WatcherSink sink) {
    }

    private final Map<UUID, Watcher> watchers = new HashMap<>();
    private final ProgressThrottler throttler;
    private String lastPhase;

    public BackupProgressNotifier() {
        this(System::nanoTime);
    }

    BackupProgressNotifier(LongSupplier clockNanos) {
        this.throttler = new ProgressThrottler(clockNanos, ProgressThrottler.DEFAULT_INTERVAL_NANOS, true);
    }

    public void subscribe(ServerPlayer player) {
        watchers.put(player.getUUID(), new Watcher(player::isAlive, (step, config) -> {
            String language = FoldBackupTranslations.resolveLanguage(player, config);
            String progress = BackupStepFormat.formatLocalized(step, language);
            player.sendSystemMessage(
                    FoldBackupTranslations.componentForLanguage(language, "foldbackup.progress.push", progress));
        }));
    }

    void subscribeForTest(UUID id, BooleanSupplier alive, Consumer<String> receiver) {
        watchers.put(id, new Watcher(alive, (step, config) -> receiver.accept(BackupStepFormat.format(step))));
    }

    public void unsubscribe(UUID playerId) {
        watchers.remove(playerId);
    }

    public void clear() {
        watchers.clear();
        throttler.reset();
        lastPhase = null;
    }

    /**
     * 向通过 /foldbackup backup 订阅的玩家推送进度更新。
     * <p>
     * 使用 {@link ProgressThrottler} 统一限速为 1 秒并开启内容去重：
     * 阶段切换时立即通知，停留在同一无变化阶段（如提交索引/GC）时不重复刷屏聊天框。
     */
    public void pushProgress(TaskStatus active, FoldBackupConfig config) {
        if (active == null) {
            throttler.reset();
            lastPhase = null;
            return;
        }
        if (watchers.isEmpty()) {
            return;
        }

        // 在迭代过程中移除已断线的玩家
        watchers.values().removeIf(watcher -> !watcher.alive().getAsBoolean());
        if (watchers.isEmpty()) {
            return;
        }

        StepProgress step = active.stepProgress();
        if (step == null) {
            return;
        }
        String canonical = BackupStepFormat.format(step);
        boolean phaseChanged = !step.phase().equals(lastPhase);
        if (!throttler.allowMessage(canonical, phaseChanged)) {
            return;
        }
        lastPhase = step.phase();

        for (Watcher watcher : watchers.values()) {
            watcher.sink().accept(step, config);
        }
    }
}
