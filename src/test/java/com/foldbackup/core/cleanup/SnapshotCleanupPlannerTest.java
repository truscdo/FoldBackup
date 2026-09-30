package com.foldbackup.core.cleanup;

import com.foldbackup.core.store.FileEntry;
import com.foldbackup.core.store.RegionEntry;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotReclamationEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCleanupPlannerTest {
    private static SnapshotManifest snapshot(String id, long time, String... paths) {
        return snapshot(id, time, Map.of(), paths);
    }

    private static SnapshotManifest snapshot(String id, long time, Map<String, Long> sizes, String... paths) {
        SnapshotManifest snapshot = new SnapshotManifest(id, time, "world");
        for (String path : paths)
            snapshot.addExtraFile(new FileEntry(path, "hash", path, sizes.getOrDefault(path, 999L), 999));
        return snapshot;
    }

    @Test
    void continuousDeletionReleasesSharedBlobOnlyAfterLastReference() {
        var sizes = Map.of("X", 100L, "Y", 200L, "Z", 50L);
        var a = snapshot("A", 1, sizes, "X", "Y");
        var b = snapshot("B", 2, sizes, "Y", "Z");
        var c = snapshot("C", 3, sizes, "X", "Z");
        var plan = SnapshotCleanupPlanner.plan(List.of(c, a, b), 360, 200);
        assertEquals(List.of(new SnapshotCleanupPlanner.Deletion(1, "A", Set.of(SnapshotCleanupPlanner.Reason.CAPACITY), 0, 0),
                new SnapshotCleanupPlanner.Deletion(2, "B", Set.of(SnapshotCleanupPlanner.Reason.CAPACITY), 200, 200)), plan.deletions());
        assertEquals(160, plan.remainingBytes());
        assertTrue(plan.targetSatisfied());
        assertEquals(2, a.getExtraFiles().size());
    }

    @Test
    void evaluatesEveryOldestFirstDeletionPrefixAndCountsSharedBlobsOnce() {
        var sizes = Map.of("X", 10L, "Y", 20L, "Z", 30L);
        var a = snapshot("A", 1, sizes, "X", "Y");
        var b = snapshot("B", 2, sizes, "Y", "Z");
        var c = snapshot("C", 3, sizes, "X", "Z");

        var estimates = SnapshotReclamationEstimator.evaluateDeletionChain(List.of(c, a, b));

        assertEquals(List.of(
                new SnapshotReclamationEstimator.DeletionEstimate(1, "A", 0, 0),
                new SnapshotReclamationEstimator.DeletionEstimate(2, "B", 20, 20),
                new SnapshotReclamationEstimator.DeletionEstimate(3, "C", 40, 60)), estimates);
        assertThrows(UnsupportedOperationException.class, () -> estimates.clear());
    }

    @Test
    void snapshotOnlyEstimateUsesCompressedSizesWithoutWritingMetadata() {
        var a = snapshot("A", 1, "X");
        var b = snapshot("B", 2, "X", "Y");
        a.getExtraFiles().getFirst().setSizeCompressed(10);
        b.getExtraFiles().getFirst().setSizeCompressed(10);
        b.getExtraFiles().get(1).setSizeCompressed(20);
        a.getSummary().setNewBlobBytes(123);

        var estimates = SnapshotReclamationEstimator.evaluateDeletionChain(List.of(b, a));

        assertEquals(List.of(
                new SnapshotReclamationEstimator.DeletionEstimate(1, "A", 0, 0),
                new SnapshotReclamationEstimator.DeletionEstimate(2, "B", 30, 30)), estimates);
        assertEquals(123, a.getSummary().getNewBlobBytes());
    }

    @Test
    void deletionChainOrdersByEpochInsteadOfDisplayId() {
        var sizes = Map.of("X", 10L, "Y", 20L);
        var later = snapshot("A", 2, sizes, "Y");
        var earlier = snapshot("Z", 1, sizes, "X");

        var estimates = SnapshotReclamationEstimator.evaluateDeletionChain(List.of(later, earlier));

        assertEquals(List.of(1L, 2L), estimates.stream()
                .map(SnapshotReclamationEstimator.DeletionEstimate::epochSeconds).toList());
        assertEquals(List.of("Z", "A"), estimates.stream()
                .map(SnapshotReclamationEstimator.DeletionEstimate::snapshotId).toList());
        assertEquals(List.of(10L, 30L), estimates.stream()
                .map(SnapshotReclamationEstimator.DeletionEstimate::cumulativeReclaimedBytes).toList());
    }

    @Test
    void deletionChainCountsRepeatedBlobReferencesWithinASnapshotOnlyOnce() {
        var a = snapshot("A", 1, Map.of("X", 12L), "X", "X");
        a.addRegion(new RegionEntry("region", "content", "X", 12, 999));

        var estimates = SnapshotReclamationEstimator.evaluateDeletionChain(List.of(a));

        assertEquals(List.of(new SnapshotReclamationEstimator.DeletionEstimate(1, "A", 12, 12)), estimates);
    }

    @Test
    void deletionChainRejectsInvalidSnapshotMetadata() {
        var a = snapshot("A", 1, "X");
        var missingPath = snapshot("B", 2, "Y");
        missingPath.getExtraFiles().getFirst().setBlobPath(" ");
        var negativeSize = snapshot("C", 3, Map.of("Z", -1L), "Z");
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotReclamationEstimator.evaluateDeletionChain(List.of(missingPath)));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotReclamationEstimator.evaluateDeletionChain(List.of(negativeSize)));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotReclamationEstimator.evaluateDeletionChain(List.of(a, a)));
    }

    @Test
    void snapshotOnlyEstimateRejectsConflictingSizesForSameBlobPath() {
        var a = snapshot("A", 1, Map.of("X", 10L), "X");
        var b = snapshot("B", 2, Map.of("X", 11L), "X");

        assertThrows(IllegalArgumentException.class,
                () -> SnapshotReclamationEstimator.evaluateDeletionChain(List.of(a, b)));
    }

    @Test
    void newSnapshotChangesOldSnapshotBenefit() {
        var sizes = Map.of("X", 100L, "Y", 200L);
        var a = snapshot("A", 1, sizes, "X");
        var b = snapshot("B", 2, sizes, "Y");
        assertEquals(List.of(1L), SnapshotCleanupPlanner.plan(List.of(a, b), 300, 100).snapshotEpochs());
        var c = snapshot("C", 3, sizes, "X");
        var plan = SnapshotCleanupPlanner.plan(List.of(a, b, c), 300, 100);
        assertEquals(List.of(1L, 2L), plan.snapshotEpochs());
        assertEquals(0, plan.deletions().getFirst().reclaimedBytes());
        assertEquals(200, plan.reclaimedBytes());
    }

    @Test
    void duplicateReferencesAcrossFilesAndRegionsCountOncePerSnapshot() {
        var a = snapshot("A", 1, Map.of("X", 12L), "X", "X");
        a.addRegion(new RegionEntry("region", "content", "X", 12, 999));
        var plan = SnapshotCleanupPlanner.plan(List.of(a), 12, 12);
        assertEquals(12, plan.reclaimedBytes());
        assertEquals(0, plan.remainingBytes());
        assertTrue(plan.targetSatisfied());
    }

    @Test
    void unattainableTargetDoesNotCountOrphansOrIndexAsReclaimable() {
        var plan = SnapshotCleanupPlanner.plan(List.of(snapshot("A", 1, Map.of("X", 10L), "X")),
                35, 30);
        assertEquals(List.of(1L), plan.snapshotEpochs());
        assertEquals(10, plan.reclaimedBytes());
        assertEquals(25, plan.remainingBytes());
        assertFalse(plan.targetSatisfied());
    }

    @Test
    void zeroTargetAndEmptyRepository() {
        assertTrue(SnapshotCleanupPlanner.plan(List.of(snapshot("A", 1, Map.of("X", 10L), "X")),
                10, 0).deletions().isEmpty());
        assertTrue(SnapshotCleanupPlanner.plan(List.of(), 0, 0).targetSatisfied());
        assertFalse(SnapshotCleanupPlanner.plan(List.of(), 0, 1).targetSatisfied());
    }

    @Test
    void duplicateEpochsAreRejectedAndResultIsImmutable() {
        var sizes = Map.of("X", 10L, "Y", 10L);
        assertThrows(IllegalArgumentException.class, () -> SnapshotCleanupPlanner.plan(List.of(
                snapshot("B", 1, sizes, "Y"), snapshot("A", 1, sizes, "X")), 20, 10));
        var plan = SnapshotCleanupPlanner.plan(List.of(snapshot("A", 1, sizes, "X")), 10, 10);
        assertThrows(UnsupportedOperationException.class, () -> plan.deletions().clear());
    }

    @Test
    void rejectsIncompleteOrInconsistentAccounting() {
        var missingPath = snapshot("A", 1, "X");
        missingPath.getExtraFiles().getFirst().setBlobPath("");
        var negativeSize = snapshot("B", 1, Map.of("X", -1L), "X");
        var tooLargeForRepository = snapshot("C", 1, Map.of("X", 11L), "X");
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotCleanupPlanner.plan(List.of(missingPath), 10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotCleanupPlanner.plan(List.of(negativeSize), 10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotCleanupPlanner.plan(List.of(tooLargeForRepository), 10, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotCleanupPlanner.plan(List.of(tooLargeForRepository, tooLargeForRepository), 10, 1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCleanupPlanner.plan(List.of(), 0, -1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCleanupPlanner.plan(List.of(), -1, 0));
    }
}
