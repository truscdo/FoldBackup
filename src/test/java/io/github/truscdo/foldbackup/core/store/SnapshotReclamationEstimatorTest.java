package io.github.truscdo.foldbackup.core.store;

import io.github.truscdo.foldbackup.core.BackupExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotReclamationEstimatorTest {

    private static final long MB = 1024L * 1024L;

    private static SnapshotManifest snapshot(String id, long epoch, Map<String, Long> sizes, String... blobPaths) {
        SnapshotManifest manifest = new SnapshotManifest(id, epoch, "world");
        long totalCompressed = 0L;
        for (int i = 0; i < blobPaths.length; i++) {
            String blobPath = blobPaths[i];
            long size = sizes.get(blobPath);
            manifest.addExtraFile(new FileEntry("file-" + i + "-" + blobPath, "hash-" + blobPath,
                    blobPath, size, size * 2));
            totalCompressed += size;
        }
        manifest.getSummary().setExtraFilesTotal(blobPaths.length);
        manifest.getSummary().setTotalCompressedBytes(totalCompressed);
        manifest.getSummary().setTotalUncompressedBytes(totalCompressed * 2);
        return manifest;
    }

    @Test
    void matchesWorkedExampleOrthogonalBreakdownAndSubsetDeletion() {
        // S1 (newest, epoch 5) .. S5 (oldest, epoch 1); S3 (epoch 3) is pinned.
        Map<String, Long> sizes = Map.of(
                "ALL", 965 * MB,
                "U1", 10 * MB,
                "U2", 5 * MB,
                "U3", 15 * MB,
                "U4", 10 * MB,
                "U5", 10 * MB,
                "P45", 15 * MB,
                "P23", 10 * MB
        );
        SnapshotManifest s5 = snapshot("S-20260901_100000", 1, sizes, "ALL", "U5", "P45");
        SnapshotManifest s4 = snapshot("S-20260901_110000", 2, sizes, "ALL", "U4", "P45");
        SnapshotManifest s3 = snapshot("S-20260901_120000", 3, sizes, "ALL", "U3", "P23");
        s3.setPinned(true);
        SnapshotManifest s2 = snapshot("S-20260901_130000", 4, sizes, "ALL", "U2", "P23");
        SnapshotManifest s1 = snapshot("S-20260901_140000", 5, sizes, "ALL", "U1");

        long indexBytes = 128 * 1024L;
        var analysis = SnapshotReclamationEstimator.analyze(List.of(s1, s2, s3, s4, s5), indexBytes);
        var summary = analysis.summary();

        assertEquals(50 * MB, summary.totalUniqueBytes());
        assertEquals(990 * MB, summary.totalSharedBytes());
        assertEquals(965 * MB, summary.globalSharedBytes());
        assertEquals(25 * MB, summary.partialSharedBytes());
        assertEquals(1040 * MB, summary.deduplicatedBlobBytes());
        assertEquals(1040 * MB + indexBytes, summary.storedBytes());
        assertEquals(1, summary.pinnedCount());
        assertEquals(15 * MB, summary.pinnedHeldBytes());

        // Identity: DeduplicatedBlobs = sum(Unique) + All + Partial
        assertEquals(summary.deduplicatedBlobBytes(),
                summary.totalUniqueBytes() + summary.globalSharedBytes() + summary.partialSharedBytes());

        // Per-snapshot Unique / Shared / Reclaim
        assertEquals(10 * MB, analysis.spaceFor(5).uniqueBytes());
        assertEquals(50 * MB, analysis.spaceFor(5).reclaimBytes());
        assertEquals(5 * MB, analysis.spaceFor(4).uniqueBytes());
        assertEquals(40 * MB, analysis.spaceFor(4).reclaimBytes());
        assertEquals(15 * MB, analysis.spaceFor(3).uniqueBytes());
        assertEquals(975 * MB, analysis.spaceFor(3).sharedBytes());
        assertTrue(analysis.spaceFor(3).pinned());
        assertEquals(0L, analysis.spaceFor(3).reclaimBytes());
        assertEquals(10 * MB, analysis.spaceFor(2).uniqueBytes());
        assertEquals(35 * MB, analysis.spaceFor(2).reclaimBytes());
        assertEquals(10 * MB, analysis.spaceFor(1).uniqueBytes());
        assertEquals(10 * MB, analysis.spaceFor(1).reclaimBytes());

        // Subset deletion {S4, S5}
        var estimate = analysis.estimateDeletion(List.of(2L, 1L));
        assertEquals(2, estimate.snapshots().size());
        assertEquals(20 * MB, estimate.individualUniqueSumBytes());
        assertEquals(15 * MB, estimate.sharedOnlyAmongSelectedBytes());
        assertEquals(35 * MB, estimate.totalReclaimBytes());
        assertEquals(summary.storedBytes(), estimate.repositoryBeforeBytes());
        assertEquals(summary.storedBytes() - 35 * MB, estimate.repositoryAfterBytes());
    }

    @Test
    void multiplePinnedSnapshotsComputeJointHeldBytes() {
        Map<String, Long> sizes = Map.of(
                "PIN_SHARED", 200L,
                "ALL", 500L,
                "UNPINNED_ONLY", 50L
        );
        SnapshotManifest p1 = snapshot("S-20260901_100000", 1, sizes, "PIN_SHARED", "ALL");
        p1.setPinned(true);
        SnapshotManifest p2 = snapshot("S-20260901_110000", 2, sizes, "PIN_SHARED", "ALL");
        p2.setPinned(true);
        SnapshotManifest normal = snapshot("S-20260901_120000", 3, sizes, "ALL", "UNPINNED_ONLY");

        var analysis = SnapshotReclamationEstimator.analyze(List.of(p1, p2, normal));
        // Neither P1 nor P2 has individual unique bytes, yet together they hold PIN_SHARED (200).
        assertEquals(0L, analysis.spaceFor(1).uniqueBytes());
        assertEquals(0L, analysis.spaceFor(2).uniqueBytes());
        assertEquals(2, analysis.summary().pinnedCount());
        assertEquals(200L, analysis.summary().pinnedHeldBytes());
    }

    @Test
    void emptyAndSingleSnapshotHaveZeroGlobalShared() {
        var empty = SnapshotReclamationEstimator.analyze(List.of(), 64L);
        assertEquals(0L, empty.summary().deduplicatedBlobBytes());
        assertEquals(64L, empty.summary().storedBytes());
        assertEquals(0L, empty.summary().globalSharedBytes());
        assertEquals(0.0, empty.summary().savedPercent());

        SnapshotManifest single = snapshot("S-20260901_100000", 1, Map.of("A", 100L), "A");
        var one = SnapshotReclamationEstimator.analyze(List.of(single));
        assertEquals(100L, one.spaceFor(1).uniqueBytes());
        assertEquals(0L, one.spaceFor(1).sharedBytes());
        assertEquals(100L, one.spaceFor(1).reclaimBytes());
        assertEquals(0L, one.summary().globalSharedBytes());
        assertEquals(0L, one.summary().totalSharedBytes());
    }

    @Test
    void sharedBytesComputedDirectlyFromDeduplicatedBlobsNotSizeMinusUnique() {
        // S1 references blob "DUP" twice internally (Size = 200), and shares "DUP" with S2.
        Map<String, Long> sizes = Map.of("DUP", 100L, "U1", 30L);
        SnapshotManifest s1 = snapshot("S-20260901_100000", 1, sizes, "DUP", "DUP", "U1");
        SnapshotManifest s2 = snapshot("S-20260901_110000", 2, sizes, "DUP");

        var analysis = SnapshotReclamationEstimator.analyze(List.of(s1, s2));
        // Size(S1) is 230, Unique(S1) is 30, but deduplicated Shared(S1) must be 100 (NOT 200!).
        assertEquals(230L, s1.getSummary().getTotalCompressedBytes());
        assertEquals(30L, analysis.spaceFor(1).uniqueBytes());
        assertEquals(100L, analysis.spaceFor(1).sharedBytes());
    }

    @Test
    void diffBaseCandidatesUsesFullRepositoryLedgerForUniqueBytes(@TempDir Path tempDir) throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        Map<String, Long> sizes = Map.of("SHARED", 100L, "ONLY_OLD", 40L, "ONLY_NEW", 60L);
        store.saveSnapshot(snapshot("S-20260901_100000", 1000L, sizes, "SHARED", "ONLY_OLD"));
        store.saveSnapshot(snapshot("S-20260901_110000", 2000L, sizes, "SHARED", "ONLY_NEW"));

        BackupExporter exporter = new BackupExporter(store);
        List<BackupExporter.SnapshotInfo> candidates = exporter.diffBaseCandidates(2000L);
        assertEquals(1, candidates.size());
        // If computed only on the filtered candidate subset, uniqueBytes would wrongly be 140 instead of 40.
        assertEquals(40L, candidates.getFirst().uniqueBytes);
    }
}
