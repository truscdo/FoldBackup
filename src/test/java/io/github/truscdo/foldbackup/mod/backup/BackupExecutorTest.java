package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.BackupResult;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.BackupStep;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class BackupExecutorTest {
    @Test void rejectedWorkerRestoresWorldAndReleasesRunSlot() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var warnings = new CopyOnWriteArrayList<String>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.shutdown();
            var executor = new BackupExecutor(Runnable::run, (key, args) -> warnings.add(key),
                    world, new BackupCoordinator(null, null, null, null), null, null, worker, tasks);
            assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS));
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
            assertFalse(tasks.hasActiveTask());
            assertEquals(List.of("foldbackup.backup.failure"), warnings);
            assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS),
                    "The next attempt must acquire the slot instead of reporting ALREADY_RUNNING");
        }
    }

    @Test void committedSnapshotCleanupFailureTellsPlayersTheSnapshotIsUsable() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var warnings = new CopyOnWriteArrayList<String>();
        var coordinator = new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
                var cleanup = new SnapshotStore.CommittedCleanupException(42L, "snapshot-42",
                        new IOException("disk full"));
                listener.accept(new BackupStep.CleanupFailed(42L, "snapshot-42", cleanup.getMessage()));
                throw cleanup;
            }
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(Runnable::run,
                    (key, args) -> warnings.add(args.length == 0 ? key : key + ":" + args[0]),
                    world, coordinator, null, new BackupScheduler(null), worker, tasks);
            assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS));
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
            assertFalse(tasks.hasActiveTask());
            assertEquals(List.of("foldbackup.progress.cleanup_failed:snapshot-42"), warnings,
                    "A committed snapshot with a failed cleanup must not be reported as a plain failure");
        }
    }

    @Test void preparationFailureRestoresOnlyStatesChangedByThisBackup() throws Exception {
        for (boolean afterSuspend : new boolean[] {false, true}) {
            var world = new WorldAccessorTest.World();
            world.failSave = !afterSuspend;
            world.failAfterSuspend = afterSuspend;
            var tasks = new TaskRegistry();
            try (var worker = Executors.newSingleThreadExecutor()) {
                var executor = new BackupExecutor(Runnable::run, (key, args) -> {}, world,
                        new BackupCoordinator(null, null, null, null), null, null, worker, tasks);
                assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS));
                world.assertRestored();
                assertFalse(executor.isBackupRunning());
                assertFalse(tasks.hasActiveTask());
            }
        }
    }

    @Test void successfulAndFailedWorkersRestoreOnMainThreadBeforeCompletingFuture() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            var world = new WorldAccessorTest.World();
            var tasks = new TaskRegistry();
            var mainQueue = new LinkedBlockingQueue<Runnable>();
            var coordinator = new BackupCoordinator(null, null, null, null) {
                @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
                    assertTrue(world.enabled.disabled);
                    assertTrue(world.disabled.disabled);
                    if (fail) throw new IOException("scan failed");
                    return null;
                }
            };
            try (var worker = Executors.newSingleThreadExecutor()) {
                var executor = new BackupExecutor(mainQueue::add, (key, args) -> {}, world, coordinator,
                        null, new BackupScheduler(null), worker, tasks);
                var future = executor.triggerBackup(true);
                mainQueue.remove().run();
                assertEquals(BackupResult.ALREADY_RUNNING, executor.triggerBackup(true).join());
                Runnable completion = mainQueue.poll(5, TimeUnit.SECONDS);
                assertNotNull(completion);
                assertFalse(future.isDone());
                var checked = future.thenRun(() -> {
                    world.assertRestored();
                    assertFalse(executor.isBackupRunning());
                });
                completion.run();
                assertEquals(fail ? BackupResult.FAILED : BackupResult.SUCCESS, future.get(2, TimeUnit.SECONDS));
                checked.get(2, TimeUnit.SECONDS);
                assertFalse(tasks.hasActiveTask());
            }
        }
    }

    @Test void blockingFailurePreservesOriginallyDisabledSaving() {
        var world = new WorldAccessorTest.World();
        world.failAfterSuspend = true;
        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(Runnable::run, (key, args) -> {}, world,
                    new BackupCoordinator(null, null, null, null), null, null, worker, new TaskRegistry());
            assertEquals(BackupResult.FAILED, executor.triggerBackupBlocking(true));
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
        }
    }

    @Test void savingIsRestoredAsSoonAsTheWorldScanCompletes() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var afterScan = new CountDownLatch(1);
        var release = new Semaphore(0);
        var coordinator = new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
                assertTrue(world.enabled.disabled, "The scan must run while saving is suspended");
                listener.accept(new BackupStep.ScanningWorld(0, 0, "", "", true));
                listener.accept(new BackupStep.PreparingSnapshot(null));
                afterScan.countDown();
                awaitUninterruptibly(release);
                return null;
            }
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(mainQueue::add, (key, args) -> {}, world, coordinator,
                    null, new BackupScheduler(null), worker, tasks, () -> 60_000L);
            var future = executor.triggerBackup(true);
            mainQueue.remove().run();
            assertTrue(afterScan.await(5, TimeUnit.SECONDS));

            assertTrue(world.enabled.disabled, "Restoring must be queued for the server thread");
            Runnable restore = mainQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(restore);
            restore.run();
            world.assertRestored();
            assertTrue(executor.isBackupRunning(), "Commit and cleanup still run in the background");

            // 扫描已结束，看门狗不得再把这次运行判为停滞。
            executor.checkSavePause(System.currentTimeMillis() + 86_400_000L);
            assertTrue(executor.isBackupRunning());
            assertFalse(world.enabled.disabled);

            release.release();
            Runnable completion = mainQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(completion);
            completion.run();
            assertEquals(BackupResult.SUCCESS, future.get(2, TimeUnit.SECONDS));
            assertFalse(tasks.hasActiveTask());
        }
    }

    @Test void stalledRunRestoresSavingWarnsPlayersAndIgnoresLateCompletion() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var warnings = new CopyOnWriteArrayList<String>();
        var started = new CountDownLatch(1);
        var coordinator = blockingScanCoordinator(started);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var scheduler = new BackupScheduler(null);
            var executor = new BackupExecutor(mainQueue::add, (key, args) -> warnings.add(key),
                    world, coordinator, null, scheduler, worker, tasks, () -> 60_000L);
            var future = executor.triggerBackup(true);
            mainQueue.remove().run();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(executor.isBackupRunning());
            assertTrue(world.enabled.disabled);

            executor.checkSavePause(System.currentTimeMillis() + 61_000L);
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
            assertFalse(tasks.hasActiveTask());
            assertEquals(List.of("foldbackup.backup.stalled"), warnings);
            assertEquals(BackupResult.FAILED, future.get(2, TimeUnit.SECONDS));

            // 被中断的 worker 最终仍会回调完成；这次回调必须被丢弃。
            Runnable late = mainQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull(late);
            late.run();
            world.assertRestored();
            assertFalse(tasks.hasActiveTask());
            assertEquals(1, warnings.size(), "A late completion must not report a second failure");
            assertEquals(BackupResult.FAILED, future.getNow(BackupResult.SUCCESS));
        }
    }

    @Test void stallThresholdIsReadOncePerRunInsteadOfEveryTick() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var started = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var coordinator = blockingScanCoordinator(started);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(mainQueue::add, (key, args) -> {}, world, coordinator,
                    null, new BackupScheduler(null), worker, tasks, () -> {
                        reads.incrementAndGet();
                        return 60_000L;
                    });
            var future = executor.triggerBackup(true);
            mainQueue.remove().run();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, reads.get(), "The threshold must be read when the run starts");

            executor.checkSavePause(System.currentTimeMillis());
            executor.checkSavePause(System.currentTimeMillis());
            assertEquals(1, reads.get(), "Tick-time checks must reuse the threshold captured by the run");
            assertTrue(executor.isBackupRunning());

            // 快照下来的阈值仍然生效。
            executor.checkSavePause(System.currentTimeMillis() + 61_000L);
            assertFalse(executor.isBackupRunning());
            assertEquals(BackupResult.FAILED, future.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void shutdownCancellationRestoresSavingWithoutBackoff() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var mainQueue = new LinkedBlockingQueue<Runnable>();
        var warnings = new AtomicInteger();
        var started = new CountDownLatch(1);
        var coordinator = blockingScanCoordinator(started);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var executor = new BackupExecutor(mainQueue::add, (key, args) -> warnings.incrementAndGet(),
                    world, coordinator, null, new BackupScheduler(null), worker, tasks, () -> 60_000L);
            var future = executor.triggerBackup(true);
            mainQueue.remove().run();
            assertTrue(started.await(5, TimeUnit.SECONDS));

            executor.cancelForShutdown();
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
            assertFalse(tasks.hasActiveTask());
            assertEquals(0, warnings.get(), "Shutdown must not broadcast a failure to players");
            assertEquals(BackupResult.FAILED, future.get(2, TimeUnit.SECONDS));
        }
    }

    /** 扫描一开始就阻塞，等待测试触发中止；被中断时按既有取消协议抛出。 */
    private static BackupCoordinator blockingScanCoordinator(CountDownLatch started) {
        return new BackupCoordinator(null, null, null, null) {
            @Override public SnapshotManifest runBackup(TaskStatus status, Consumer<BackupStep> listener) throws IOException {
                listener.accept(new BackupStep.ScanningWorld(0, 0, "", "", false));
                started.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("cancelled");
                }
                return null;
            }
        };
    }

    private static void awaitUninterruptibly(Semaphore semaphore) {
        boolean interrupted = false;
        while (true) {
            try {
                semaphore.acquire();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
