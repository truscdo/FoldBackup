package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.cleanup.SnapshotCleanupPlanner;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RetentionPolicyTest {
    private SnapshotManifest snapshot(String id, long time, long size, String path) {
        SnapshotManifest sn = new SnapshotManifest(id, time, "world");
        sn.addExtraFile(new FileEntry(path, "hash", path, size, 999));
        sn.setTags(List.of("manual"));
        return sn;
    }

    @Test void ageUsesNowAndStrictBoundaryEvenWhenSpaceIsSatisfied() {
        long now = 200000;
        var snapshots = List.of(snapshot("old", now - 86401, 10, "A"),
                snapshot("boundary", now - 86400, 10, "B"), snapshot("new", now - 1, 10, "C"));
        var plan = new RetentionPolicy().setMaxAgeDays(1).plan(snapshots, 30, 100, now);
        assertEquals(List.of(now - 86401), plan.snapshotEpochs());
        assertEquals(Set.of(SnapshotCleanupPlanner.Reason.AGE), plan.deletions().getFirst().reasons());
    }

    @Test void disabledAndZeroLimitsNeverDelete() {
        var snapshots = List.of(snapshot("old", 1, 10, "A"));
        assertTrue(new RetentionPolicy().plan(snapshots, 10, 0, 200000).deletions().isEmpty());
        assertTrue(new RetentionPolicy().setEnabled(false).setMaxAgeDays(1)
                .setMaxSizeBytes(1).setMinFreeSpaceBytes(100)
                .plan(snapshots, 10, 0, 200000).deletions().isEmpty());
    }

    @Test void spaceDeficitsUseMaximumAndCanDeleteManualSnapshotsWithinAgeLimit() {
        var snapshots = List.of(snapshot("a", 100, 10, "A"), snapshot("b", 101, 10, "B"),
                snapshot("c", 102, 10, "C"));
        var plan = new RetentionPolicy().setMaxAgeDays(1).setMaxSizeBytes(20).setMinFreeSpaceBytes(15)
                .plan(snapshots, 30, 0, 103);
        assertEquals(List.of(100L, 101L), plan.snapshotEpochs());
        assertEquals(Set.of(SnapshotCleanupPlanner.Reason.CAPACITY, SnapshotCleanupPlanner.Reason.FREE_SPACE),
                plan.deletions().getFirst().reasons());
        assertEquals(Set.of(SnapshotCleanupPlanner.Reason.FREE_SPACE), plan.deletions().get(1).reasons());
        assertTrue(plan.targetSatisfied());
    }

    @Test void completesAgeDeletionsThenContinuesForCapacity() {
        var snapshots = List.of(snapshot("a", 1, 20, "A"), snapshot("b", 2, 20, "A"),
                snapshot("c", 200000, 20, "A"), snapshot("d", 200001, 10, "B"));
        var plan = new RetentionPolicy().setMaxAgeDays(1).setMaxSizeBytes(10)
                .plan(snapshots, 30, 100, 200002);
        assertEquals(List.of(1L, 2L, 200000L), plan.snapshotEpochs());
        assertEquals(List.of(0L, 0L, 20L), plan.deletions().stream()
                .map(SnapshotCleanupPlanner.Deletion::reclaimedBytes).toList());
    }

    @Test void ageContinuesAfterCapacityHasBeenSatisfied() {
        var plan = new RetentionPolicy().setMaxAgeDays(1).setMaxSizeBytes(20)
                .plan(List.of(snapshot("a", 1, 10, "A"), snapshot("b", 2, 10, "B")),
                        20, 100, 200000);
        assertEquals(List.of(1L, 2L), plan.snapshotEpochs());
    }

    @Test void rejectsNegativeAndOverflowingLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy().setMaxSizeBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy().setMinFreeSpaceBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy().setMaxAgeDays(-1));
        assertThrows(ArithmeticException.class, () -> new RetentionPolicy().setMaxAgeDays(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> SnapshotCleanupPlanner.plan(
                List.of(snapshot("a", 1, Long.MAX_VALUE, "A"), snapshot("b", 2, 1, "B")),
                Long.MAX_VALUE, 1));
    }
}
