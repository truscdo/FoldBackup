package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.BackupResult;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.BackupStep;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TickHandlerTest {

    private static void advanceTicks(BackupScheduler scheduler, BackupExecutor executor,
            boolean hasPlayers, int ticks, long nowMillis) {
        for (int i = 0; i < ticks; i++) {
            TickHandler.tickScheduledBackup(scheduler, executor, hasPlayers, nowMillis);
        }
    }

    @Test void deferredBackupConsumesNoticeOnceAndTriggersAfterMinInterval() throws Exception {
        var scheduler = new BackupScheduler(() -> 1, () -> 10);
        scheduler.markBackupComplete();
        long base = scheduler.getLastBackupTime();

        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var runs = new AtomicInteger();
        var done = new CountDownLatch(1);
        var now = new AtomicLong(base);
        var coordinator = new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) {
                runs.incrementAndGet();
                return null;
            }
        };

        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(Runnable::run, (key, args) -> {}, world, coordinator,
                    scheduler, worker, tasks, () -> 0L, now::get) {
                @Override public CompletableFuture<BackupResult> triggerBackup(boolean force, Runnable onAccepted) {
                    var f = super.triggerBackup(force, onAccepted);
                    f.whenComplete((r, e) -> done.countDown());
                    return f;
                }
            };

            // 6 × 200 tick = 60_000 ms (1 分钟在线时间)，此时仅过了 1 分钟墙钟时间 (< 10 分钟)
            now.set(base + 60_000L);
            advanceTicks(scheduler, executor, true, 1200, now.get());
            assertEquals(0, runs.get(), "冷却期内不得启动备份");
            assertFalse(scheduler.consumeMinIntervalDeferralNotice(),
                    "首次进入冷却延后状态时应已消费边沿通知");

            // 后续即使玩家下线，每 200 tick 仍继续跳过且不恢复通知标志
            now.set(base + 5 * 60_000L);
            advanceTicks(scheduler, executor, false, 600, now.get());
            assertEquals(0, runs.get());
            assertFalse(scheduler.consumeMinIntervalDeferralNotice());

            // 墙钟到达 minIntervalMinutes 后，下一个 200 tick 检查点立刻补做备份
            now.set(base + 10 * 60_000L);
            advanceTicks(scheduler, executor, false, 200, now.get());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
            assertEquals(0, scheduler.getAccumulatedOnlineMinutes());
            assertTrue(scheduler.consumeMinIntervalDeferralNotice(),
                    "备份完成后应重置延后通知标志以供下一轮使用");
        }
    }

    @Test void runningBackupDoesNotRetriggerOrConsumeDeferralNotice() throws Exception {
        var scheduler = new BackupScheduler(() -> 1, () -> 0);
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var started = new CountDownLatch(1);
        var release = new Semaphore(0);
        var runs = new AtomicInteger();
        var coordinator = new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) {
                runs.incrementAndGet();
                started.countDown();
                try {
                    release.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };

        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(mainQueue::add, (key, args) -> {}, world, coordinator,
                    scheduler, worker, tasks, () -> 0L);

            long now = System.currentTimeMillis() + 60_000L;
            advanceTicks(scheduler, executor, true, 1200, now);
            mainQueue.remove().run();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
            assertTrue(executor.isBackupRunning());

            // 备份尚在进行中时继续推进多个 200 tick，不得再次触发或消费延后通知
            advanceTicks(scheduler, executor, true, 600, now + 30_000L);
            assertEquals(1, runs.get());
            assertTrue(scheduler.consumeMinIntervalDeferralNotice());

            release.release();
            mainQueue.take().run();
        }
    }

    @Test void failedScheduledBackupResetsAccrualAndWaitsFullInterval() throws Exception {
        var scheduler = new BackupScheduler(() -> 1, () -> 0);
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var runs = new AtomicInteger();
        var firstDone = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var coordinator = new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
                if (runs.incrementAndGet() == 1) {
                    throw new IOException("transient scan error");
                }
                return null;
            }
        };

        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(Runnable::run, (key, args) -> {}, world, coordinator,
                    scheduler, worker, tasks, () -> 0L) {
                @Override public CompletableFuture<BackupResult> triggerBackup(boolean force, Runnable onAccepted) {
                    var f = super.triggerBackup(force, onAccepted);
                    f.whenComplete((r, e) -> {
                        if (firstDone.getCount() > 0) firstDone.countDown();
                        else secondDone.countDown();
                    });
                    return f;
                }
            };

            long now = System.currentTimeMillis();
            advanceTicks(scheduler, executor, true, 1200, now);
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
            assertEquals(0, scheduler.getAccumulatedOnlineMinutes());

            // 失败后下一个 200 tick 检查点（无论是否在线）不得立即重试，须重新攒满完整 interval
            advanceTicks(scheduler, executor, false, 200, now + 10_000L);
            advanceTicks(scheduler, executor, true, 200, now + 20_000L);
            assertEquals(1, runs.get());

            advanceTicks(scheduler, executor, true, 1000, now + 70_000L);
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertEquals(2, runs.get());
        }
    }
}
