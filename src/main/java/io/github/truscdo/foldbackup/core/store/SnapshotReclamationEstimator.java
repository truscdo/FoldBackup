package io.github.truscdo.foldbackup.core.store;

import java.util.*;

/**
 * Estimates snapshot space usage and blob reclamation from in-memory manifest metadata
 * without scanning {@code blobs/}.
 */
public final class SnapshotReclamationEstimator {
    private SnapshotReclamationEstimator() {}

    /**
     * One step in a deletion chain. The step's reclaimed bytes depend on deleting all
     * older unpinned snapshots first; cumulativeReclaimedBytes includes this and all prior steps.
     */
    public record DeletionEstimate(long epochSeconds, String snapshotId, long reclaimedBytes,
                                   long cumulativeReclaimedBytes) {}

    /** Per-snapshot space breakdown derived from the full repository reference ledger. */
    public record SnapshotSpace(long epochSeconds, String snapshotId, long uniqueBytes,
                                long sharedBytes, long reclaimBytes, boolean pinned) {}

    /** Repository-level space summary derived from the in-memory index and {@code index.db} size. */
    public record RepositorySpaceSummary(long deduplicatedBlobBytes, long indexBytes,
                                         long totalUniqueBytes, long totalSharedBytes,
                                         long globalSharedBytes, long partialSharedBytes,
                                         long logicalBytes, int pinnedCount, long pinnedHeldBytes) {
        public long storedBytes() {
            return Math.addExact(deduplicatedBlobBytes, indexBytes);
        }

        public double savedPercent() {
            if (logicalBytes <= 0L) {
                return 0.0;
            }
            double pct = (1.0 - (double) deduplicatedBlobBytes / logicalBytes) * 100.0;
            return Math.max(0.0, Math.min(100.0, pct));
        }
    }

    /** One selected snapshot in a subset deletion estimate. */
    public record SnapshotDeletionItem(long epochSeconds, String snapshotId, int regions,
                                       int files, long uniqueBytes) {}

    /** Exact reclamation estimate for deleting an arbitrary non-empty subset of snapshots. */
    public record SubsetDeletionEstimate(List<SnapshotDeletionItem> snapshots,
                                         long individualUniqueSumBytes,
                                         long sharedOnlyAmongSelectedBytes,
                                         long totalReclaimBytes,
                                         long repositoryBeforeBytes,
                                         long repositoryAfterBytes) {
        public SubsetDeletionEstimate {
            snapshots = List.copyOf(snapshots);
        }
    }

    /** Full-repository reference ledger and precomputed space metrics. */
    public static final class RepositorySpaceAnalysis {
        private final Map<Long, SnapshotManifest> snapshotsByEpoch;
        private final Map<Long, Set<String>> pathsByEpoch;
        private final Map<String, Long> blobSizes;
        private final Map<String, Integer> totalRefs;
        private final Map<Long, SnapshotSpace> spacesByEpoch;
        private final List<DeletionEstimate> deletionChain;
        private final RepositorySpaceSummary summary;

        private RepositorySpaceAnalysis(Map<Long, SnapshotManifest> snapshotsByEpoch,
                                        Map<Long, Set<String>> pathsByEpoch,
                                        Map<String, Long> blobSizes,
                                        Map<String, Integer> totalRefs,
                                        Map<Long, SnapshotSpace> spacesByEpoch,
                                        List<DeletionEstimate> deletionChain,
                                        RepositorySpaceSummary summary) {
            this.snapshotsByEpoch = snapshotsByEpoch;
            this.pathsByEpoch = pathsByEpoch;
            this.blobSizes = blobSizes;
            this.totalRefs = totalRefs;
            this.spacesByEpoch = Map.copyOf(spacesByEpoch);
            this.deletionChain = List.copyOf(deletionChain);
            this.summary = summary;
        }

        public RepositorySpaceSummary summary() {
            return summary;
        }

        public SnapshotSpace spaceFor(long epochSeconds) {
            return spacesByEpoch.get(epochSeconds);
        }

        public Map<Long, SnapshotSpace> spacesByEpoch() {
            return spacesByEpoch;
        }

        public List<DeletionEstimate> deletionChain() {
            return deletionChain;
        }

        /**
         * Algorithm 1: exact reclamation estimate for deleting an arbitrary non-empty subset of snapshots.
         */
        public SubsetDeletionEstimate estimateDeletion(Collection<Long> targetEpochs) {
            if (targetEpochs == null || targetEpochs.isEmpty()) {
                throw new IllegalArgumentException("Target snapshot epochs must not be empty");
            }
            Set<Long> uniqueEpochs = new LinkedHashSet<>(targetEpochs);
            List<SnapshotDeletionItem> items = new ArrayList<>(uniqueEpochs.size());
            long individualUniqueSum = 0L;
            for (long epoch : uniqueEpochs) {
                SnapshotManifest manifest = snapshotsByEpoch.get(epoch);
                SnapshotSpace space = spacesByEpoch.get(epoch);
                if (manifest == null || space == null) {
                    throw new IllegalArgumentException("Unknown snapshot epoch: " + epoch);
                }
                int regions = manifest.getSummary() != null
                        ? manifest.getSummary().getRegionsTotal()
                        : (manifest.getRegions() != null ? manifest.getRegions().size() : 0);
                int files = manifest.getSummary() != null
                        ? manifest.getSummary().getExtraFilesTotal()
                        : (manifest.getExtraFiles() != null ? manifest.getExtraFiles().size() : 0);
                items.add(new SnapshotDeletionItem(epoch, manifest.getSnapshotId(), regions, files,
                        space.uniqueBytes()));
                individualUniqueSum = Math.addExact(individualUniqueSum, space.uniqueBytes());
            }

            long totalReclaim = computeSubsetReclaim(uniqueEpochs, pathsByEpoch, totalRefs, blobSizes);
            long sharedOnlyAmongSelected = Math.subtractExact(totalReclaim, individualUniqueSum);
            long before = summary.storedBytes();
            long after = Math.max(0L, before - totalReclaim);
            return new SubsetDeletionEstimate(items, individualUniqueSum, sharedOnlyAmongSelected,
                    totalReclaim, before, after);
        }
    }

    /**
     * Analyzes all surviving snapshots in the repository using in-memory manifest metadata only.
     */
    public static RepositorySpaceAnalysis analyze(List<SnapshotManifest> snapshots) {
        return analyze(snapshots, 0L);
    }

    /**
     * Analyzes all surviving snapshots in the repository using in-memory manifest metadata
     * combined with the single {@code index.db} file size.
     */
    public static RepositorySpaceAnalysis analyze(List<SnapshotManifest> snapshots, long indexBytes) {
        if (indexBytes < 0L) {
            throw new IllegalArgumentException("indexBytes must be nonnegative");
        }
        Map<String, Long> sizes = collectBlobSizes(snapshots);
        List<SnapshotManifest> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparingLong(SnapshotManifest::getEpochSeconds));

        Map<Long, SnapshotManifest> snapshotsByEpoch = new HashMap<>(sorted.size());
        Map<Long, Set<String>> pathsByEpoch = new HashMap<>(sorted.size());
        Map<String, Integer> totalRefs = new HashMap<>(sizes.size());
        List<Long> pinnedEpochs = new ArrayList<>();
        long logicalBytes = 0L;

        for (SnapshotManifest snapshot : sorted) {
            long epochSeconds = snapshot.getEpochSeconds();
            String id = Objects.requireNonNull(snapshot.getSnapshotId(), "snapshotId");
            Set<String> paths = new HashSet<>();
            if (snapshot.getRegions() != null) {
                snapshot.getRegions().forEach(entry -> paths.add(entry.getBlobPath()));
            }
            if (snapshot.getExtraFiles() != null) {
                snapshot.getExtraFiles().forEach(entry -> paths.add(entry.getBlobPath()));
            }
            if (pathsByEpoch.putIfAbsent(epochSeconds, paths) != null) {
                throw new IllegalArgumentException("Duplicate snapshot epoch: " + epochSeconds);
            }
            snapshotsByEpoch.put(epochSeconds, snapshot);
            if (snapshot.isPinned()) {
                pinnedEpochs.add(epochSeconds);
            }
            if (snapshot.getSummary() != null) {
                logicalBytes = Math.addExact(logicalBytes, snapshot.getSummary().getTotalCompressedBytes());
            }
            for (String path : paths) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("Missing blob path in " + id);
                }
                Long size = sizes.get(path);
                if (size == null || size < 0) {
                    throw new IllegalArgumentException("Missing or negative manifest blob size: " + path);
                }
                totalRefs.merge(path, 1, Math::addExact);
            }
        }

        // Algorithm 2: continuous oldest-first deletion chain skipping pinned snapshots
        Map<String, Integer> simRefs = new HashMap<>(totalRefs);
        List<DeletionEstimate> chain = new ArrayList<>(sorted.size());
        Map<Long, Long> reclaimByEpoch = new HashMap<>(sorted.size());
        long cumulativeReclaimed = 0L;
        for (SnapshotManifest snapshot : sorted) {
            if (snapshot.isPinned()) {
                continue;
            }
            long step = 0L;
            long epochSeconds = snapshot.getEpochSeconds();
            for (String path : pathsByEpoch.get(epochSeconds)) {
                int remaining = simRefs.compute(path, (key, count) -> count - 1);
                if (remaining == 0) {
                    step = Math.addExact(step, sizes.get(path));
                }
            }
            cumulativeReclaimed = Math.addExact(cumulativeReclaimed, step);
            chain.add(new DeletionEstimate(epochSeconds, snapshot.getSnapshotId(), step, cumulativeReclaimed));
            reclaimByEpoch.put(epochSeconds, cumulativeReclaimed);
        }

        // Per-snapshot Unique(S) and Shared(S) directly from blobs(S)
        Map<Long, SnapshotSpace> spacesByEpoch = new HashMap<>(sorted.size());
        long totalUniqueBytes = 0L;
        for (SnapshotManifest snapshot : sorted) {
            long epochSeconds = snapshot.getEpochSeconds();
            long unique = 0L;
            long shared = 0L;
            for (String path : pathsByEpoch.get(epochSeconds)) {
                int ref = totalRefs.get(path);
                long size = sizes.get(path);
                if (ref == 1) {
                    unique = Math.addExact(unique, size);
                } else {
                    shared = Math.addExact(shared, size);
                }
            }
            totalUniqueBytes = Math.addExact(totalUniqueBytes, unique);
            long reclaim = snapshot.isPinned() ? 0L : reclaimByEpoch.getOrDefault(epochSeconds, 0L);
            spacesByEpoch.put(epochSeconds, new SnapshotSpace(
                    epochSeconds, snapshot.getSnapshotId(), unique, shared, reclaim, snapshot.isPinned()));
        }

        // Repository-level Shared = All + Partial
        int snapshotCount = sorted.size();
        long deduplicatedBlobBytes = 0L;
        long totalSharedBytes = 0L;
        long globalSharedBytes = 0L;
        for (Map.Entry<String, Integer> entry : totalRefs.entrySet()) {
            long size = sizes.get(entry.getKey());
            int ref = entry.getValue();
            deduplicatedBlobBytes = Math.addExact(deduplicatedBlobBytes, size);
            if (ref >= 2) {
                totalSharedBytes = Math.addExact(totalSharedBytes, size);
                if (snapshotCount >= 2 && ref == snapshotCount) {
                    globalSharedBytes = Math.addExact(globalSharedBytes, size);
                }
            }
        }
        long partialSharedBytes = Math.subtractExact(totalSharedBytes, globalSharedBytes);

        // Held(P) = Reclaim(P) via Algorithm 1
        long pinnedHeldBytes = pinnedEpochs.isEmpty()
                ? 0L
                : computeSubsetReclaim(pinnedEpochs, pathsByEpoch, totalRefs, sizes);

        RepositorySpaceSummary summary = new RepositorySpaceSummary(
                deduplicatedBlobBytes, indexBytes, totalUniqueBytes, totalSharedBytes,
                globalSharedBytes, partialSharedBytes, logicalBytes,
                pinnedEpochs.size(), pinnedHeldBytes);

        return new RepositorySpaceAnalysis(snapshotsByEpoch, pathsByEpoch, sizes, totalRefs,
                spacesByEpoch, chain, summary);
    }

    /** Convenience helper for estimating reclamation of an arbitrary subset of snapshots. */
    public static SubsetDeletionEstimate estimateSubsetDeletion(List<SnapshotManifest> allSnapshots,
                                                                Collection<Long> targetEpochs,
                                                                long indexBytes) {
        return analyze(allSnapshots, indexBytes).estimateDeletion(targetEpochs);
    }

    /**
     * Estimate from manifest metadata only. Sizes are taken from each entry's
     * sizeCompressed field; this does not check whether the referenced blob exists
     * or whether its current filesystem size still matches the manifest.
     */
    public static List<DeletionEstimate> evaluateDeletionChain(List<SnapshotManifest> snapshots) {
        return analyze(snapshots, 0L).deletionChain();
    }

    /** Builds the distinct {@code blobPath -> sizeCompressed} map across all snapshots, rejecting invalid entries. */
    public static Map<String, Long> collectBlobSizes(List<SnapshotManifest> snapshots) {
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
        return sizes;
    }

    private static long computeSubsetReclaim(Collection<Long> subsetEpochs,
                                             Map<Long, Set<String>> pathsByEpoch,
                                             Map<String, Integer> totalRefs,
                                             Map<String, Long> sizes) {
        Map<String, Integer> refInSubset = new HashMap<>();
        for (long epoch : subsetEpochs) {
            Set<String> paths = pathsByEpoch.get(epoch);
            if (paths == null) {
                continue;
            }
            for (String path : paths) {
                refInSubset.merge(path, 1, Math::addExact);
            }
        }
        long reclaimed = 0L;
        for (Map.Entry<String, Integer> entry : refInSubset.entrySet()) {
            String path = entry.getKey();
            if (entry.getValue().equals(totalRefs.get(path))) {
                reclaimed = Math.addExact(reclaimed, sizes.get(path));
            }
        }
        return reclaimed;
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
