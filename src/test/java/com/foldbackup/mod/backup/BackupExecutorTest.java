package com.foldbackup.mod.backup;

import com.foldbackup.core.BackupResult;
import com.foldbackup.core.backup.BackupCoordinator;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskStatus;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BackupExecutorTest {
    @Test void rejectedWorkerRestoresWorldAndReleasesRunSlot() throws Exception {
        var world = new WorldAccessorTest.World();
        var tasks = new TaskRegistry();
        var notifications = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.shutdown();
            var executor = new BackupExecutor(Runnable::run, notifications::incrementAndGet,
                    world, new BackupCoordinator(null, null, null, null), null, null, worker, tasks);
            assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS));
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
            assertFalse(tasks.hasActiveTask());
            assertEquals(1, notifications.get());
            assertEquals(BackupResult.FAILED, executor.triggerBackup(true).get(2, TimeUnit.SECONDS),
                    "The next attempt must acquire the slot instead of reporting ALREADY_RUNNING");
        }
    }

    @Test void preparationFailureRestoresOnlyStatesChangedByThisBackup() throws Exception {
        for (boolean afterSuspend : new boolean[] {false, true}) {
            var world = new WorldAccessorTest.World();
            world.failSave = !afterSuspend;
            world.failAfterSuspend = afterSuspend;
            var tasks = new TaskRegistry();
            try (var worker = Executors.newSingleThreadExecutor()) {
                var executor = new BackupExecutor(Runnable::run, () -> {}, world,
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
                @Override public SnapshotManifest runBackup(TaskStatus status) throws IOException {
                    assertTrue(world.enabled.disabled);
                    assertTrue(world.disabled.disabled);
                    if (fail) throw new IOException("scan failed");
                    return null;
                }
            };
            try (var worker = Executors.newSingleThreadExecutor()) {
                var executor = new BackupExecutor(mainQueue::add, () -> {}, world, coordinator,
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
            var executor = new BackupExecutor(Runnable::run, () -> {}, world,
                    new BackupCoordinator(null, null, null, null), null, null, worker, new TaskRegistry());
            assertEquals(BackupResult.FAILED, executor.triggerBackupBlocking(true));
            world.assertRestored();
            assertFalse(executor.isBackupRunning());
        }
    }
}
