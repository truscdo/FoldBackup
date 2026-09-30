package com.foldbackup.core.backup;

import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskStatus;
import com.foldbackup.core.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupProgressTest {
    @TempDir Path temp;

    private Path world() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "small world metadata");
        return world;
    }

    private BackupCoordinator coordinator(Path world, SnapshotStore store) {
        return new BackupCoordinator(new BackupContext(world, "world", ""), store,
                new RegionProcessor(store::getIndex, new BlobStore(temp.resolve("store"), 6), 6), null);
    }

    private TaskStatus status() {
        return new TaskRegistry().startTask(TaskType.BACKUP).setRunning();
    }

    @Test
    void publishesSameStepsAsTaskStatusAndPreservesExactBytes() throws Exception {
        var store = new SnapshotStore(temp.resolve("store"));
        var status = status();
        var steps = new ArrayList<BackupStep>();
        var manifest = coordinator(world(), store).runBackup(status, step -> {
            assertSame(step, status.stepProgress());
            if (step instanceof BackupStep.Completed completed)
                assertEquals(completed.snapshotId(), status.artifactId());
            steps.add(step);
        });
        assertEquals(List.of("SCANNING_WORLD", "PREPARING_SNAPSHOT",
                "COMMITTING_SNAPSHOT", "COMPLETED"), steps.stream().map(BackupStep::phase).distinct().toList());
        var scan = steps.stream().filter(BackupStep.ScanningWorld.class::isInstance)
                .map(BackupStep.ScanningWorld.class::cast).filter(BackupStep.ScanningWorld::complete)
                .findFirst().orElseThrow();
        assertTrue(scan.complete());
        assertEquals(1, scan.finishedCount());
        assertEquals(scan.discoveredCount(), scan.processedCount());
        var completed = assertInstanceOf(BackupStep.Completed.class, steps.getLast());
        assertTrue(completed.newBytes() > 0 && completed.newBytes() < 1024 * 1024);
        assertEquals(manifest.getSummary().getNewBlobBytes(), completed.newBytes());
        assertNotNull(store.loadSnapshot(completed.epochSeconds()));
        assertEquals(TaskStatus.Phase.RUNNING, status.phase(), "Caller finalizes the task registry");
    }

    @Test
    void scanFailureIsNotReportedAsCommittedAndKeepsFailureCounts() throws Exception {
        Path world = world();
        Files.createDirectory(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "corrupt");
        var store = new SnapshotStore(temp.resolve("store"));
        var status = status();
        var steps = new ArrayList<BackupStep>();
        assertThrows(IOException.class, () -> coordinator(world, store).runBackup(status, steps::add));
        var scan = steps.stream().filter(BackupStep.ScanningWorld.class::isInstance)
                .map(BackupStep.ScanningWorld.class::cast).filter(BackupStep.ScanningWorld::complete)
                .findFirst().orElseThrow();
        assertEquals(1, scan.failures());
        assertEquals(1, scan.processedCount());
        assertEquals(2, scan.finishedCount());
        assertEquals(2, scan.discoveredCount());
        assertInstanceOf(BackupStep.Failed.class, steps.getLast());
        assertEquals(TaskStatus.Phase.FAILED, status.phase());
        assertNull(status.artifactId());
        assertNull(store.loadLatestSnapshot());
    }

    @Test
    void cleanupFailureRetainsCommittedIdentityForBothConsumers() throws Exception {
        var store = new SnapshotStore(temp.resolve("store")) {
            @Override public void saveSnapshot(SnapshotManifest manifest, RetentionPolicy retention) throws IOException {
                super.saveSnapshot(manifest, retention);
                throw new CommittedCleanupException(manifest.getEpochSeconds(), manifest.getSnapshotId(),
                        new IOException("cleanup unavailable"));
            }
        };
        var status = status();
        var steps = new ArrayList<BackupStep>();
        Path world = world();
        var failure = assertThrows(SnapshotStore.CommittedCleanupException.class,
                () -> coordinator(world, store).runBackup(status, step -> {
                    assertSame(step, status.stepProgress());
                    if (step instanceof BackupStep.CleanupFailed cleanup) {
                        assertEquals(TaskStatus.Phase.FAILED, status.phase());
                        assertEquals(cleanup.snapshotId(), status.artifactId());
                    }
                    steps.add(step);
                }));
        var cleanup = assertInstanceOf(BackupStep.CleanupFailed.class, steps.getLast());
        assertEquals(failure.epochSeconds(), cleanup.epochSeconds());
        assertEquals(failure.snapshotId(), cleanup.snapshotId());
        assertNotNull(store.loadSnapshot(cleanup.epochSeconds()));
        assertTrue(steps.stream().noneMatch(BackupStep.Completed.class::isInstance));
    }

    @Test
    void observerFailureDoesNotTurnSuccessfulCommitIntoFailedBackup() throws Exception {
        var store = new SnapshotStore(temp.resolve("store"));
        var status = status();
        var manifest = coordinator(world(), store).runBackup(status, step -> {
            throw new IllegalStateException("terminal unavailable");
        });
        assertInstanceOf(BackupStep.Completed.class, status.stepProgress());
        assertNotNull(store.loadSnapshot(manifest.getEpochSeconds()));
        assertEquals(manifest.getSnapshotId(), status.artifactId());
    }
}
