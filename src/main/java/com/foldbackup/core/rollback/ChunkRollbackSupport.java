package com.foldbackup.core.rollback;

import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.RegionEntry;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.format.LrhFormat;
import com.foldbackup.core.format.ChunkData;
import com.foldbackup.core.format.RegionStream;
import java.io.ByteArrayOutputStream;
import com.foldbackup.core.format.EntityRegionCodec;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Shared utility methods for chunk rollback — used by both
 * {@link ChunkRollbackCoordinator} (file-based) and
 * {@link com.foldbackup.mod.rollback.InMemoryChunkRollbackHandler} (in-memory).
 * <p>
 * No Minecraft dependencies, pure JUnit-testable.
 */
public class ChunkRollbackSupport {

    public enum EntitySnapshotState { REGION_MISSING, CHUNK_MISSING, PRESENT }

    public record ResolvedEntitySnapshot(EntitySnapshotState state,
            EntityRegionCodec.EntityChunkSnapshot snapshot) {}

    public static String entityRegionFilename(String dimensionSubPath, int regionX, int regionZ) {
        return dimensionSubPath + "entities/r." + regionX + "." + regionZ + ".mca";
    }

    /** Uses the already resolved manifest, so latest cannot change between the two reads. */
    public static ResolvedEntitySnapshot resolveEntities(SnapshotManifest manifest, BlobStore blobs,
            int chunkX, int chunkZ, String dimensionSubPath) throws IOException {
        RegionEntry entry = findRegionEntry(manifest, entityRegionFilename(dimensionSubPath, chunkX >> 5, chunkZ >> 5));
        if (entry == null) return new ResolvedEntitySnapshot(EntitySnapshotState.REGION_MISSING,
                EntityRegionCodec.empty(chunkX, chunkZ));
        var chunk = readChunk(blobs, entry, chunkX, chunkZ);
        if (chunk == null) return new ResolvedEntitySnapshot(EntitySnapshotState.CHUNK_MISSING,
                EntityRegionCodec.empty(chunkX, chunkZ));
        return new ResolvedEntitySnapshot(EntitySnapshotState.PRESENT,
                EntityRegionCodec.read(chunk.getRawChunk(), chunkX, chunkZ));
    }

    public static EntityRegionCodec.Recovery recoverEntities(SnapshotManifest manifest, BlobStore blobs,
            int x, int z, String dimensionSubPath) throws IOException {
        RegionEntry entry = findRegionEntry(manifest, entityRegionFilename(dimensionSubPath, x >> 5, z >> 5));
        var chunk = entry == null ? null : readChunk(blobs, entry, x, z);
        return EntityRegionCodec.recover(chunk == null ? EntityRegionCodec.empty(x, z).nbt() : chunk.getRawChunk(), x, z);
    }

    /**
     * Resolved metadata and the requested chunk only; an absent chunk is null.
     */
    public record ResolvedSnapshot(
            SnapshotManifest manifest,
            RegionEntry regionEntry,
            ChunkData chunk) {
    }

    /** Retains only the selected NBT, but consumes the full frame to validate its checksum and hash. */
    private static ChunkData readChunk(BlobStore blobs, RegionEntry entry, int x, int z) throws IOException {
        int target = (z & 31) * 32 + (x & 31);
        var bytes = new ByteArrayOutputStream();
        LrhFormat.read(blobs.getRootDir().resolve(entry.getBlobPath()), new RegionStream.Consumer() {
            boolean selected;
            public void begin(int index, int timestamp) { selected = index == target; }
            public void data(byte[] buffer, int offset, int length) throws IOException {
                if (selected) bytes.write(buffer, offset, length);
            }
            public void end() {}
        });
        return bytes.size() == 0 ? null : new ChunkData(bytes.toByteArray(), x, z);
    }

    /**
     * Load a snapshot and stream its LRH blob, retaining only the requested chunk.
     * <p>
     * The {@code dimensionSubPath} parameter accounts for Minecraft's dimension
     * directory structure within the world save. For the overworld it is
     * {@code ""}; for the Nether {@code "DIM-1/"}; for the End {@code "DIM1/"};
     * for custom datapack dimensions {@code "dimensions/&lt;namespace&gt;/&lt;path&gt;/"}.
     *
     * @param sm               snapshot manager
     * @param bs               blob store
     * @param chunkX           global chunk X coordinate
     * @param chunkZ           global chunk Z coordinate
     * @param epochSeconds     target snapshot epoch, or {@code null} for latest
     * @param dimensionSubPath dimension-relative path prefix
     *                         (e.g. {@code ""} for overworld,
     *                         {@code "DIM-1/"} for Nether)
     * @return resolved snapshot data
     * @throws IOException if the snapshot is missing, the region is not found,
     *                     or I/O fails
     */
    public static ResolvedSnapshot resolveChunk(
            SnapshotStore sm, BlobStore bs,
            int chunkX, int chunkZ,
            @Nullable Long epochSeconds,
            String dimensionSubPath) throws IOException {

        SnapshotManifest manifest = resolveSnapshot(sm, epochSeconds);
        if (manifest == null) {
            throw new IOException(epochSeconds != null
                    ? "Snapshot not found at epoch: " + epochSeconds
                    : "No snapshots available");
        }

        int regionX = chunkX >> 5, regionZ = chunkZ >> 5;
        String regionFilename = dimensionSubPath + "region/r." + regionX + "." + regionZ + ".mca";
        RegionEntry entry = findRegionEntry(manifest, regionFilename);
        if (entry == null) {
            throw new IOException("Region r." + regionX + "." + regionZ
                    + ".mca not found in snapshot " + manifest.getSnapshotId()
                    + " at path: " + regionFilename);
        }

        return new ResolvedSnapshot(manifest, entry, readChunk(bs, entry, chunkX, chunkZ));
    }

    /**
     * Convenience overload for the overworld (dimension sub-path is empty).
     *
     * @see #resolveChunk(SnapshotStore, BlobStore, int, int, Long, String)
     */
    public static ResolvedSnapshot resolveChunk(
            SnapshotStore sm, BlobStore bs,
            int chunkX, int chunkZ,
            @Nullable Long epochSeconds) throws IOException {
        return resolveChunk(sm, bs, chunkX, chunkZ, epochSeconds, "");
    }

    /**
     * Derive the dimension sub-path from a world-level directory name.
     * <p>
     * Maps Minecraft dimension folder names to the path prefix used in
     * snapshot manifest region filenames.
     *
     * @param worldDirName the last component of the world path
     *                     (e.g. {@code "DIM-1"}, {@code "DIM1"})
     * @return the dimension sub-path with trailing slash
     */
    public static String deriveDimensionSubPath(String worldDirName) {
        return switch (worldDirName) {
            case "DIM-1" -> "DIM-1/";
            case "DIM1" -> "DIM1/";
            default -> "";
        };
    }

    public static String deriveDimensionSubPath(Path dimensionDirectory) {
        Path directory = dimensionDirectory.toAbsolutePath().normalize();
        for (Path parent = directory; parent != null; parent = parent.getParent()) {
            if (parent.getFileName() != null && parent.getFileName().toString().equals("dimensions"))
                return parent.getParent().relativize(directory).toString().replace('\\', '/') + "/";
        }
        return directory.getFileName() == null ? "" : deriveDimensionSubPath(directory.getFileName().toString());
    }

    /**
     * Resolve the target snapshot by epoch or load the latest.
     *
     * @param sm         snapshot manager
     * @param epochSeconds target epoch, or {@code null} for latest
     * @return the snapshot manifest, or {@code null} if none exist
     */
    @Nullable
    public static SnapshotManifest resolveSnapshot(
            SnapshotStore sm, @Nullable Long epochSeconds) {
        if (epochSeconds != null) {
            return sm.loadSnapshot(epochSeconds);
        }
        List<SnapshotManifest> all = sm.loadAllSnapshots();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Find a region entry by filename within a snapshot manifest.
     *
     * @param manifest the snapshot manifest
     * @param filename region filename (e.g. {@code "region/r.0.0.mca"})
     * @return the matching region entry, or {@code null} if not found
     */
    @Nullable
    public static RegionEntry findRegionEntry(
            SnapshotManifest manifest, String filename) {
        if (manifest.getRegions() == null)
            return null;
        for (RegionEntry entry : manifest.getRegions()) {
            if (entry.getFilename().equals(filename)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Check whether an {@code .mca} file is currently locked by another process.
     * <p>
     * CLI-only safety check; not used in the mod layer.
     * Uses {@link FileChannel#tryLock()} to detect whether the file is already
     * open exclusively (e.g. by a running Minecraft server).
     *
     * @param regionFile path to the {@code .mca} file
     * @return {@code true} if the file is not locked (or does not exist);
     *         {@code false} if it is locked by another process
     */
    public static boolean tryLockRegionFile(Path regionFile) {
        if (!Files.exists(regionFile)) {
            return true;
        }
        try (var channel = FileChannel.open(regionFile,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var lock = channel.tryLock();
            if (lock != null) {
                lock.release();
                return true;
            }
            return false;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
