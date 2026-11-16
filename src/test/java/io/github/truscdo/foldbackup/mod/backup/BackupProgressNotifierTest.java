package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.backup.BackupStep;
import io.github.truscdo.foldbackup.core.backup.ScanStats;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BackupProgressNotifierTest {

    @Test
    void deduplicatesUnchangedNonScanningStagesAndNotifiesImmediatelyOnPhaseChange() {
        var clock = new AtomicLong(1_000_000_000L);
        var notifier = new BackupProgressNotifier(clock::get);
        var messages = new ArrayList<String>();
        var alive = new AtomicBoolean(true);
        notifier.subscribeForTest(UUID.randomUUID(), alive::get, messages::add);

        var status = new TaskRegistry().startTask(TaskType.BACKUP).setRunning();
        status.setStepProgress(new BackupStep.ScanningWorld(0, 0, "region/r.0.0.mca", "", false, 1, 0, 0));

        notifier.pushProgress(status, null);
        assertEquals(1, messages.size());

        // 1 秒未满：不推送
        clock.addAndGet(500_000_000L);
        status.setStepProgress(new BackupStep.ScanningWorld(1, 0, "region/r.1.0.mca", "", false, 2, 0, 0));
        notifier.pushProgress(status, null);
        assertEquals(1, messages.size());

        // 刚过 100ms 即切换阶段到 PreparingSnapshot：立刻穿透推送
        clock.addAndGet(100_000_000L);
        status.setStepProgress(new BackupStep.PreparingSnapshot(new ScanStats(2, 1, 0, 0)));
        notifier.pushProgress(status, null);
        assertEquals(2, messages.size());
        assertTrue(messages.getLast().contains("Preparing snapshot"));

        // 停留在同一阶段超过多秒，不应每秒重复刷同一条消息
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(2_000_000_000L);
            notifier.pushProgress(status, null);
        }
        assertEquals(2, messages.size(), "unchanged PreparingSnapshot must not spam players");

        // 切换到 CommittingSnapshot：再次穿透推送一次
        status.setStepProgress(new BackupStep.CommittingSnapshot("S-20261116_100000"));
        notifier.pushProgress(status, null);
        assertEquals(3, messages.size());
        assertTrue(messages.getLast().contains("Committing snapshot"));
    }
}
