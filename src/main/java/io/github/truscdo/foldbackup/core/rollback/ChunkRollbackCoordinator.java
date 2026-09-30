package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.format.EntityRegionCodec;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Coordinates chunk-level rollback from a backup snapshot.
 * <p>
 * Offline only: the server must be stopped. Minecraft's world session lock is
 * held while preparing and writing block and entity region files.
 * <p>
 * The rollback works by:
 * <ol>
 * <li>Loading the target snapshot from the index</li>
 * <li>Streaming and validating the .lrh blob</li>
 * <li>Retaining only the target chunk NBT</li>
 * <li>Streaming the current .mca file (or an empty region if absent)</li>
 * <li>Replacing the target cell while forwarding all other chunks unchanged</li>
 * <li>Staging both region files and any external chunks, then replacing each atomically</li>
 * <li>Restoring already replaced files if a later write fails</li>
 * </ol>
 * <p>
 * This class is in {@code core} and has <b>no Minecraft dependencies</b>.
 * The CLI supplies the dimension directory and snapshot path prefix.
 */
public class ChunkRollbackCoordinator {

    private final SnapshotStore snapshotManager;
    private final BlobStore blobStore;
    private final int compressionLevel;

    public ChunkRollbackCoordinator(SnapshotStore snapshotManager, BlobStore blobStore, int compressionLevel) {
        this.snapshotManager = snapshotManager;
        this.blobStore = blobStore;
        this.compressionLevel = compressionLevel;
    }

    /**
     * Roll back a single chunk from a snapshot to the given region directory.
     *
     * @param regionDir        the {@code region/} subdirectory for this dimension
     * @param chunkX           global chunk X coordinate
     * @param chunkZ           global chunk Z coordinate
     * @param epochSeconds     snapshot epoch to restore from, or {@code null} for the
     *                         latest
     * @param dimensionSubPath dimension-relative path prefix for snapshot lookup
     *                         (e.g. {@code ""} for overworld, {@code "DIM-1/"} for
     *                         Nether)
     * @return result describing what happened
     * @throws IOException on I/O failure
     */
    public ChunkRollbackResult rollbackChunk(Path regionDir, int chunkX, int chunkZ,
            Long epochSeconds,
            String dimensionSubPath) throws IOException {
        // ── compute region coordinates and grid index ──
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        int gridIndex = (chunkZ & 31) * 32 + (chunkX & 31);

        // ── resolve snapshot metadata and stream the requested chunk ──
        ChunkRollbackSupport.ResolvedSnapshot resolved;
        try {
            resolved = ChunkRollbackSupport.resolveChunk(snapshotManager, blobStore,
                    chunkX, chunkZ, epochSeconds, dimensionSubPath);
        } catch (IOException e) {
            return new ChunkRollbackResult(false, e.getMessage());
        }

        // ── check target chunk exists in the snapshot ──
        if (resolved.chunk() == null) {
            return new ChunkRollbackResult(false,
                    "Chunk [" + chunkX + ", " + chunkZ
                            + "] is empty in snapshot " + resolved.manifest().getSnapshotId());
        }

        // ── build region file path ──
        Path regionFile = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");

        Path entityFile = regionDir.resolveSibling("entities").resolve(regionFile.getFileName());
        boolean existed = Files.exists(regionFile);
        String entityReport;
        try (var writer = new OfflineRollbackWriter(regionDir)) {
            if (!ChunkRollbackSupport.tryLockRegionFile(regionFile) || !ChunkRollbackSupport.tryLockRegionFile(entityFile))
                return new ChunkRollbackResult(false, "Region or entity file is locked; stop the server before rollback");
            McaRegionUpdate currentRegion = new McaRegionUpdate(regionFile, regionX, regionZ,
                    McaRegionUpdate.mtime(regionFile), gridIndex, resolved.chunk().getRawChunk(),
                    (int) (System.currentTimeMillis() / 1000L));
            McaRegionUpdate currentEntities = null;
            try {
                var recovery = ChunkRollbackSupport.recoverEntities(resolved.manifest(), blobStore, chunkX, chunkZ, dimensionSubPath);
                if (recovery.preserveAll()) throw new IOException("Entity identity could not be determined; " + recovery.issues());
                var snapshot = recovery.snapshot();
                List<String> issues = new ArrayList<>(recovery.issues());
                Set<UUID> protectedIds = new HashSet<>(recovery.skippedIds());
                var scan = writer.scanEntityRegions(snapshot, entityFile, gridIndex, issues);
                protectedIds.addAll(scan.conflicts());
                var old = scan.targetChunk() == null ? EntityRegionCodec.empty(chunkX, chunkZ)
                        : EntityRegionCodec.read(scan.targetChunk(), chunkX, chunkZ);
                long oldCount = old.entities().stream()
                        .mapToLong(tree -> EntityRegionCodec.treeUuids(tree).size()).sum();
                List<EntityRegionCodec.EntityTag> incoming = new ArrayList<>(snapshot.entities());
                List<EntityRegionCodec.EntityTag> preserved = new ArrayList<>();
                int skipped = recovery.skippedTrees();
                boolean changed;
                do {
                    changed = false;
                    for (var tree : old.entities()) {
                        Set<UUID> ids = EntityRegionCodec.treeUuids(tree);
                        if (!Collections.disjoint(ids, protectedIds) && !preserved.contains(tree)) {
                            preserved.add(tree); changed |= protectedIds.addAll(ids);
                        }
                    }
                    for (var it = incoming.iterator(); it.hasNext();) {
                        var tree = it.next();
                        Set<UUID> ids = EntityRegionCodec.treeUuids(tree);
                        if (!Collections.disjoint(ids, protectedIds)) {
                            changed |= protectedIds.addAll(ids); it.remove(); skipped++;
                            issues.add("Skipped entity tree " + ids);
                        }
                    }
                } while (changed);
                long restored = incoming.stream().mapToLong(t -> EntityRegionCodec.treeUuids(t).size()).sum();
                long preservedCount = preserved.stream()
                        .mapToLong(tree -> EntityRegionCodec.treeUuids(tree).size()).sum();
                long removed = oldCount - preservedCount;
                incoming.addAll(preserved);
                var merged = EntityRegionCodec.withEntities(snapshot, incoming);
                currentEntities = new McaRegionUpdate(entityFile, regionX, regionZ,
                        McaRegionUpdate.mtime(entityFile), gridIndex,
                        merged.isEmpty() ? null : merged.nbt(), (int) (System.currentTimeMillis() / 1000L));
                entityReport = "entities restored=" + restored + ", removed=" + removed + ", skipped trees=" + skipped
                        + ", preserved=" + preservedCount;
                if (!issues.isEmpty()) entityReport += "; " + String.join("; ", issues);
            } catch (IOException | java.io.UncheckedIOException e) {
                entityReport = "entities restored=0, removed=0, skipped=all (count unknown), current entity files preserved: " + e.getMessage();
            }
            LogUtil.getLogger("foldbackup-entities").info("Chunk [{}, {}]: {}", chunkX, chunkZ, entityReport);
            writer.write(currentRegion, currentEntities, compressionLevel);
        } catch (IOException e) {
            return new ChunkRollbackResult(false, e.getMessage());
        }

        String detail = "Chunk [" + chunkX + ", " + chunkZ
                + "] restored from " + resolved.manifest().getSnapshotId()
                + " (r." + regionX + "." + regionZ + ".mca)"
                + (existed ? "" : ", region file was created") + "; " + entityReport;
        return new ChunkRollbackResult(true, detail);
    }
}
