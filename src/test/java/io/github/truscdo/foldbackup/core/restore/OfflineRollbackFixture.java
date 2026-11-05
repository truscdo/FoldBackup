package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestoreSource;
import io.github.truscdo.foldbackup.core.export.SnapshotZipExport;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a real Anvil world, backs it up, mutates it, and rolls it back offline.
 * <p>
 * Every world version carries its tag inside chunk NBT, so assertions name the version a chunk
 * actually holds instead of only comparing hashes. Modification times are fixed constants: the
 * checker compares size and mtime before it reads content, and a test that wants the content path
 * must move the timestamp too.
 */
final class OfflineRollbackFixture {
    static final int COMPRESSION = 6;
    /** Compressed size above 255 region sectors forces the external {@code .mcc} form. */
    static final int OVERSIZED_CHUNK = 1_500_000;
    static final long MTIME_V1 = 1_700_000_000_000L;
    static final long MTIME_V2 = 1_700_000_100_000L;
    static final long MTIME_V3 = 1_700_000_200_000L;

    private final Path world;
    private final Path storeRoot;
    private final Path archiveRoot;
    private long lastEpoch;

    OfflineRollbackFixture(Path root) {
        this.world = root.resolve("world");
        this.storeRoot = root.resolve("store");
        this.archiveRoot = root.resolve("archives");
    }

    Path world() {
        return world;
    }

    Path storeRoot() {
        return storeRoot;
    }

    Path archiveRoot() {
        return archiveRoot;
    }

    // ── world construction ──

    void writeWorld(String version) throws IOException {
        writeWorld(version, MTIME_V1);
    }

    /** Recreates the whole world with the version tag written into every file. */
    void writeWorld(String version, long mtimeMillis) throws IOException {
        deleteTree(world);
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        stamp(world.resolve("level.dat"), "level-" + version, mtimeMillis);
        stamp(world.resolve("playerdata/a.dat"), "player-" + version, mtimeMillis);

        Region region = new Region(0, 0, mtimeMillis);
        region.setChunk(0, chunk(0, utf8("chunk-0-0-" + version)));
        region.setChunk(1, chunk(1, utf8("chunk-1-0-" + version)));
        region.setTimestamp(0, seconds(mtimeMillis));
        region.setTimestamp(1, seconds(mtimeMillis));
        AnvilTestSupport.writeRegionAnvil(regionFile(), region, COMPRESSION);
        Files.setLastModifiedTime(regionFile(), FileTime.fromMillis(mtimeMillis));
    }

    /** Replaces one chunk in the existing region, keeping every other chunk. */
    void writeChunk(int index, byte[] content, long mtimeMillis) throws IOException {
        Region region = AnvilTestSupport.readRegionAnvil(regionFile());
        region.setChunk(index, chunk(index, content));
        region.setTimestamp(index, seconds(mtimeMillis));
        AnvilTestSupport.writeRegionAnvil(regionFile(), region, COMPRESSION);
        Files.setLastModifiedTime(regionFile(), FileTime.fromMillis(mtimeMillis));
    }

    void removeChunk(int index, long mtimeMillis) throws IOException {
        Region region = AnvilTestSupport.readRegionAnvil(regionFile());
        region.setChunk(index, null);
        AnvilTestSupport.writeRegionAnvil(regionFile(), region, COMPRESSION);
        Files.setLastModifiedTime(regionFile(), FileTime.fromMillis(mtimeMillis));
    }

    void addFile(String relative, String content) throws IOException {
        Path file = world.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    String readFile(String relative) throws IOException {
        return Files.readString(world.resolve(relative), StandardCharsets.UTF_8);
    }

    Path regionFile() {
        return world.resolve("region/r.0.0.mca");
    }

    byte[] chunkBytes(int index) throws IOException {
        ChunkData chunk = AnvilTestSupport.readRegionAnvil(regionFile()).getChunk(index);
        return chunk == null ? null : chunk.getRawChunk();
    }

    String chunkText(int index) throws IOException {
        byte[] bytes = chunkBytes(index);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    List<String> mccFiles() throws IOException {
        Path regionDir = world.resolve("region");
        if (!Files.isDirectory(regionDir)) return List.of();
        try (var listing = Files.list(regionDir)) {
            return listing.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".mcc"))
                    .sorted()
                    .toList();
        }
    }

    /** Every regular file relative to the world root, ignoring locks and staging leftovers. */
    Map<String, byte[]> allFiles() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(world)) return files;
        try (var walk = Files.walk(world)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = world.relativize(path).toString().replace('\\', '/');
                if (relative.equals("session.lock") || relative.contains(".foldbackup")) continue;
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    // ── backup ──

    /**
     * Captures a snapshot and returns its manifest.
     * <p>
     * The producer derives the epoch from the wall clock, so two backups inside the same second
     * collide. Waiting keeps the diff-archive case on two distinct epochs.
     */
    SnapshotManifest backup() throws IOException {
        while (Instant.now().getEpochSecond() <= lastEpoch) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for a distinct snapshot epoch", e);
            }
        }
        SnapshotManifest manifest = SnapshotCreation.create(world, storeRoot, 1, event -> { });
        lastEpoch = manifest.getEpochSeconds();
        return manifest;
    }

    // ── offline restore ──

    RestoreOperation.Outcome restoreLatest(boolean deleteExtra) throws IOException {
        return restoreAt(new SnapshotStore(storeRoot).loadLatestSnapshot(), world, deleteExtra);
    }

    RestoreOperation.Outcome restoreTo(Path output, boolean deleteExtra) throws IOException {
        return restoreAt(new SnapshotStore(storeRoot).loadLatestSnapshot(), output, deleteExtra);
    }

    RestoreOperation.Outcome restoreAt(SnapshotManifest manifest, Path output, boolean deleteExtra)
            throws IOException {
        try (var source = new SnapshotRestoreSource(manifest, new BlobStore(storeRoot, COMPRESSION))) {
            return restoreWith(source, output, deleteExtra, false);
        }
    }

    RestoreOperation.Outcome restoreWith(RestoreSource source, Path output, boolean deleteExtra, boolean dryRun)
            throws IOException {
        return RestoreOperation.run(source, output,
                new RestoreOperation.Options(dryRun, deleteExtra, 1, COMPRESSION), null);
    }

    // ── archives ──

    Path createFullArchive(long epochSeconds) throws IOException {
        return new ArchiveCreator(new SnapshotStore(storeRoot),
                new BlobStore(storeRoot, ArchiveDefaults.EXPORT_LEVEL), ArchiveDefaults.ARCHIVE_LEVEL)
                .createFullWithProgress(epochSeconds, archiveRoot, true, 1, null);
    }

    Path createDiffArchive(long baseEpochSeconds, long diffEpochSeconds) throws IOException {
        return new ArchiveCreator(new SnapshotStore(storeRoot),
                new BlobStore(storeRoot, ArchiveDefaults.EXPORT_LEVEL), ArchiveDefaults.ARCHIVE_LEVEL)
                .createDiffWithProgress(baseEpochSeconds, diffEpochSeconds, archiveRoot, 1, null);
    }

    RestoreOperation.Outcome restoreArchive(Path archiveFile, Path output, boolean deleteExtra) throws IOException {
        try (var source = ArchiveRestoreSource.open(archiveRoot, archiveFile)) {
            return restoreWith(source, output, deleteExtra, false);
        }
    }

    // ── zip export ──

    Path exportZip(String snapshotId, Path output) throws IOException {
        return SnapshotZipExport.export(new SnapshotStore(storeRoot),
                new BlobStore(storeRoot, ArchiveDefaults.EXPORT_LEVEL), snapshotId, output, 1, null);
    }

    void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                Path destination = root.resolve(entry.getName()).normalize();
                if (!destination.startsWith(root))
                    throw new IOException("Zip entry escapes target: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(destination)) {
                    input.transferTo(output);
                }
            }
        }
    }

    // ── helpers ──

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Pseudo-random bytes that zlib cannot shrink below the external-chunk threshold. */
    static byte[] incompressible(int size) {
        byte[] data = new byte[size];
        new Random(20261105L).nextBytes(data);
        return data;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) Files.deleteIfExists(path);
    }

    private static ChunkData chunk(int index, byte[] content) {
        return new ChunkData(content, index % Region.REGION_DIMENSION, index / Region.REGION_DIMENSION);
    }

    private static void stamp(Path file, String content, long mtimeMillis) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
    }

    private static int seconds(long millis) {
        return (int) (millis / 1000L);
    }
}
