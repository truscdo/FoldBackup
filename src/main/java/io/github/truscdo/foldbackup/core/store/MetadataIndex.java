package io.github.truscdo.foldbackup.core.store;


import java.util.*;

/**
 * In-memory index for all snapshots, entry metadata, and content-hash lookups.
 * <p>
 * Built by scanning {@code index.db} at startup, then kept in memory for the
 * lifetime of the server.  All content-hash dedup lookups hit this index
 * <em>with zero disk I/O</em>.
 * <p>
 * Data layout in memory:
 * <pre>
 * snapshots              List&lt;SnapshotManifest&gt;  — epoch newest first
 * metadataByHash         Map&lt;hash, EntryMetadata&gt;
 * snapshotsByEpoch       Map&lt;epochSeconds, SnapshotManifest&gt;
 * snapshotEntryHashes    Map&lt;epochSeconds, List&lt;metadataHash&gt;&gt;
 * contentIndex           Map&lt;(type, blobHash), blobPath&gt;                     ← 去重索引
 * </pre>
 */
public class MetadataIndex {
    private boolean frozen;

    synchronized MetadataIndex freeze() {
        frozen = true;
        return this;
    }

    private void requireMutable() {
        if (frozen) throw new UnsupportedOperationException("Committed index is read-only");
    }

    synchronized boolean sameContents(MetadataIndex other) {
        return snapshots.equals(other.snapshots) && snapshotsByEpoch.equals(other.snapshotsByEpoch)
                && epochsBySnapshotId.equals(other.epochsBySnapshotId)
                && metadataByHash.equals(other.metadataByHash)
                && snapshotEntryHashes.equals(other.snapshotEntryHashes);
    }

    private final List<SnapshotManifest> snapshots = new ArrayList<>();
    private final Map<Long, SnapshotManifest> snapshotsByEpoch = new HashMap<>();
    private final Map<String, Long> epochsBySnapshotId = new HashMap<>();
    private final Map<String, EntryMetadata> metadataByHash = new HashMap<>();
    private final Map<Long, List<String>> snapshotEntryHashes = new HashMap<>();

    private record ContentKey(EntryMetadata.Type type, String hash) {}
    private final Map<ContentKey, String> contentIndex = new HashMap<>();

    // ──────────────────────────────────────────────
    //  Mutation  (called during index build and writes)
    // ──────────────────────────────────────────────

    /** Copy mutable index containers for a staged commit; manifests are not mutated by commits. */
    public synchronized MetadataIndex copy() {
        MetadataIndex copy = new MetadataIndex();
        copy.snapshots.addAll(snapshots);
        copy.snapshotsByEpoch.putAll(snapshotsByEpoch);
        copy.epochsBySnapshotId.putAll(epochsBySnapshotId);
        copy.metadataByHash.putAll(metadataByHash);
        snapshotEntryHashes.forEach((epoch, hashes) ->
                copy.snapshotEntryHashes.put(epoch, new ArrayList<>(hashes)));
        copy.contentIndex.putAll(contentIndex);
        return copy;
    }

    /** Add a snapshot to the in-memory index (inserted at front, newest first). */
    public synchronized void addSnapshot(SnapshotManifest manifest,
                                          List<String> entryMetadataHashes) {
        requireMutable();
        long epochSeconds = manifest.getEpochSeconds();
        String snapshotId = manifest.getSnapshotId();
        if (snapshotsByEpoch.containsKey(epochSeconds))
            throw new IllegalArgumentException("Duplicate snapshot epoch: " + epochSeconds);
        if (epochsBySnapshotId.containsKey(snapshotId))
            throw new IllegalArgumentException("Duplicate snapshot ID: " + snapshotId);
        SnapshotManifest snapshot = manifest.copy();
        snapshots.add(snapshot);
        snapshots.sort(Comparator.comparingLong(SnapshotManifest::getEpochSeconds).reversed());
        snapshotsByEpoch.put(epochSeconds, snapshot);
        epochsBySnapshotId.put(snapshotId, epochSeconds);
        snapshotEntryHashes.put(epochSeconds,
                new ArrayList<>(entryMetadataHashes));
    }

    /** Store a single entry metadata record. */
    public synchronized void addMetadata(EntryMetadata meta) {
        requireMutable();
        String metaHash = meta.getMetadataHash();
        if (metadataByHash.containsKey(metaHash)) return; // already present

        metadataByHash.put(metaHash, meta);

        // Build content-index entry
        contentIndex.putIfAbsent(new ContentKey(meta.getType(), meta.getBlobHash()), meta.getBlobPath());
    }

    /** Remove a snapshot and all its entry references. */
    public synchronized void removeSnapshot(long epochSeconds) {
        requireMutable();
        SnapshotManifest removed = snapshotsByEpoch.remove(epochSeconds);
        if (removed != null) {
            snapshots.removeIf(s -> s.getEpochSeconds() == epochSeconds);
            epochsBySnapshotId.remove(removed.getSnapshotId());
        }
        List<String> hashes = snapshotEntryHashes.remove(epochSeconds);
        if (hashes != null) {
            // Remove metadata refs — but only if no other snapshot references them
            // We don't eagerly delete metadata; GC will rebuild the active set.
        }
    }

    // ──────────────────────────────────────────────
    //  Queries
    // ──────────────────────────────────────────────

    /** All snapshots, newest-first, detached from the internal state. */
    public synchronized List<SnapshotManifest> getSnapshots() {
        return snapshots.stream().map(SnapshotManifest::copy).toList();
    }

    /** Load a single snapshot manifest. */
    public synchronized SnapshotManifest loadSnapshot(long epochSeconds) {
        SnapshotManifest snapshot = snapshotsByEpoch.get(epochSeconds);
        return snapshot == null ? null : snapshot.copy();
    }

    /** Resolve a user-facing ID to its unique internal epoch identity. */
    public synchronized OptionalLong findEpochBySnapshotId(String snapshotId) {
        Long epochSeconds = epochsBySnapshotId.get(snapshotId);
        return epochSeconds == null ? OptionalLong.empty() : OptionalLong.of(epochSeconds);
    }

    /** Load the snapshot with the latest Epoch, or {@code null} when empty. */
    public synchronized SnapshotManifest loadLatestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.get(0).copy();
    }

    /** Get an entry metadata by its hash. */
    public synchronized EntryMetadata getMetadata(String metadataHash) {
        return metadataByHash.get(metadataHash);
    }

    /** Get all metadata hashes for a snapshot's entries. */
    public synchronized List<String> getEntryHashes(long epochSeconds) {
        List<String> h = snapshotEntryHashes.get(epochSeconds);
        return h != null ? List.copyOf(h) : List.of();
    }

    /**
     * Look up a stored blob path by content type and content hash.
     *
     * @param type          Region LRH or regular-file Zstd blob
     * @param contentHash   XXH3-128 hex string of the blob identity
     * @return blobPath if found, or {@code null}
     */
    public synchronized String findBlobByContentHash(EntryMetadata.Type type, String contentHash) {
        return contentIndex.get(new ContentKey(type, contentHash));
    }

    /**
     * Collect all blob paths referenced by surviving snapshots (for GC).
     */
    public synchronized Set<String> collectAllBlobPaths() {
        Set<String> blobs = new HashSet<>();
        for (List<String> hashes : snapshotEntryHashes.values()) {
            for (String h : hashes) {
                EntryMetadata meta = metadataByHash.get(h);
                if (meta != null) {
                    blobs.add(meta.getBlobPath());
                }
            }
        }
        return blobs;
    }

    /** Total number of entry metadata records. */
    public synchronized int metadataCount() {
        return metadataByHash.size();
    }

    /** Total number of snapshots. */
    public synchronized int snapshotCount() {
        return snapshots.size();
    }

    /** Return all metadata entries (for full serialization). */
    public synchronized List<EntryMetadata> getAllMetadata() {
        return List.copyOf(metadataByHash.values());
    }

    /**
     * Remove every {@link EntryMetadata} whose hash is not in
     * {@code keepHashes}.  Also rebuilds the content index so it
     * stays consistent with the surviving metadata records.
     * <p>
     * Called during GC mark-and-sweep to prevent unbounded metadata
     * accumulation when snapshots are deleted.
     *
     * @param keepHashes set of metadata hashes to preserve
     */
    public synchronized void retainOnlyMetadata(Set<String> keepHashes) {
        requireMutable();
        Map<String, EntryMetadata> newMetadata = new HashMap<>();
        Map<ContentKey, String> newContentIndex = new HashMap<>();

        for (Map.Entry<String, EntryMetadata> e : metadataByHash.entrySet()) {
            if (keepHashes.contains(e.getKey())) {
                EntryMetadata meta = e.getValue();
                newMetadata.put(e.getKey(), meta);
                newContentIndex.putIfAbsent(new ContentKey(meta.getType(), meta.getBlobHash()), meta.getBlobPath());
            }
        }

        metadataByHash.clear();
        metadataByHash.putAll(newMetadata);
        contentIndex.clear();
        contentIndex.putAll(newContentIndex);
    }
}
