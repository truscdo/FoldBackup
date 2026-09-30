package com.foldbackup.core.store;

import java.util.*;

/** Estimates blob bytes released by deleting snapshots consecutively, oldest first. */
public final class SnapshotReclamationEstimator {
    private SnapshotReclamationEstimator() {}

    /**
     * One step in a deletion chain. The step's reclaimed bytes depend on deleting all
     * older snapshots first; cumulativeReclaimedBytes includes this and all prior steps.
     */
    public record DeletionEstimate(long epochSeconds, String snapshotId, long reclaimedBytes,
                                   long cumulativeReclaimedBytes) {}

    /**
     * Estimate from manifest metadata only. Sizes are taken from each entry's
     * sizeCompressed field; this does not check whether the referenced blob exists
     * or whether its current filesystem size still matches the manifest.
     */
    public static List<DeletionEstimate> evaluateDeletionChain(List<SnapshotManifest> snapshots) {
        Map<String, Long> sizes = new HashMap<>();
        for (SnapshotManifest snapshot : snapshots) {
            if (snapshot.getRegions() != null) {
                for (RegionEntry entry : snapshot.getRegions())
                    addManifestSize(sizes, entry.getBlobPath(), entry.getSizeCompressed());
            }
            if (snapshot.getExtraFiles() != null) {
                for (FileEntry entry : snapshot.getExtraFiles())
                    addManifestSize(sizes, entry.getBlobPath(), entry.getSizeCompressed());
            }
        }
        return evaluate(snapshots, sizes);
    }

    private static List<DeletionEstimate> evaluate(List<SnapshotManifest> snapshots,
                                                    Map<String, Long> sizes) {
        List<SnapshotManifest> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparingLong(SnapshotManifest::getEpochSeconds));
        Map<Long, Set<String>> pathsBySnapshot = new HashMap<>();
        Map<String, Integer> references = new HashMap<>();
        for (SnapshotManifest snapshot : sorted) {
            long epochSeconds = snapshot.getEpochSeconds();
            String id = Objects.requireNonNull(snapshot.getSnapshotId(), "snapshotId");
            Set<String> paths = new HashSet<>();
            if (snapshot.getRegions() != null)
                snapshot.getRegions().forEach(entry -> paths.add(entry.getBlobPath()));
            if (snapshot.getExtraFiles() != null)
                snapshot.getExtraFiles().forEach(entry -> paths.add(entry.getBlobPath()));
            if (pathsBySnapshot.putIfAbsent(epochSeconds, paths) != null)
                throw new IllegalArgumentException("Duplicate snapshot epoch: " + epochSeconds);
            for (String path : paths) {
                if (path == null || path.isBlank())
                    throw new IllegalArgumentException("Missing blob path in " + id);
                Long size = sizes.get(path);
                if (size == null || size < 0)
                    throw new IllegalArgumentException("Missing or negative manifest blob size: " + path);
                references.merge(path, 1, Math::addExact);
            }
        }

        List<DeletionEstimate> estimates = new ArrayList<>(sorted.size());
        long reclaimed = 0;
        for (SnapshotManifest snapshot : sorted) {
            long step = 0;
            long epochSeconds = snapshot.getEpochSeconds();
            for (String path : pathsBySnapshot.get(epochSeconds)) {
                int remaining = references.compute(path, (key, count) -> count - 1);
                if (remaining == 0) step = Math.addExact(step, sizes.get(path));
            }
            reclaimed = Math.addExact(reclaimed, step);
            estimates.add(new DeletionEstimate(epochSeconds, snapshot.getSnapshotId(), step, reclaimed));
        }
        return List.copyOf(estimates);
    }

    private static void addManifestSize(Map<String, Long> sizes, String path, long size) {
        if (path == null || path.isBlank())
            throw new IllegalArgumentException("Missing blob path in snapshot metadata");
        if (size < 0)
            throw new IllegalArgumentException("Negative blob size: " + path);
        Long previous = sizes.putIfAbsent(path, size);
        if (previous != null && previous != size)
            throw new IllegalArgumentException("Conflicting manifest blob sizes: " + path);
    }
}
