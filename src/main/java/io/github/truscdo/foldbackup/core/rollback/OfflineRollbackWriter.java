package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.format.EntityRegionCodec;
import io.github.truscdo.foldbackup.core.format.McaHasher;
import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.McaStreamWriter;
import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;

/** Holds Minecraft's world lock throughout preparation and commit. Never writes into a running world. */
final class OfflineRollbackWriter implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Path dimensionDir;
    private final Path worldRoot;
    private final MoveOperation move;
    @FunctionalInterface interface MoveOperation { void move(Path source, Path target) throws IOException; }

    OfflineRollbackWriter(Path regionDir) throws IOException {
        this(regionDir, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    OfflineRollbackWriter(Path regionDir, MoveOperation move) throws IOException {
        this.move = move;
        dimensionDir = regionDir.toAbsolutePath().normalize().getParent();
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) throw new IOException("World directory does not exist");
        Path root = dimensionDir;
        if (Set.of("DIM-1", "DIM1").contains(root.getFileName().toString())) root = root.getParent();
        for (Path p = dimensionDir; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("dimensions")) { root = p.getParent(); break; }
        }
        worldRoot = root;
        channel = FileChannel.open(root.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("World is locked; stop the server before rollback");
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("World is locked; stop the server before rollback", e);
        }
    }

    EntityScan scanEntityRegions(EntityRegionCodec.EntityChunkSnapshot snapshot, Path targetFile, int targetIndex,
            List<String> issues) throws IOException {
        Set<UUID> incoming = EntityRegionCodec.uuids(snapshot);
        Set<UUID> conflicts = new HashSet<>();
        byte[][] targetChunk = {null};
        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        try (var paths = Files.walk(worldRoot)) {
            var iterator = paths.filter(this::isEntityRegion).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                boolean sameFile = path.toAbsolutePath().normalize().equals(normalizedTarget);
                if (!sameFile && incoming.isEmpty()) continue;
                int[] coords = io.github.truscdo.foldbackup.core.format.AnvilFormat.parseRegionCoords(path.getFileName().toString());
                ByteArrayOutputStream chunkBytes = new ByteArrayOutputStream();
                int[] activeIndex = {-1};
                boolean[] capture = {false};
                boolean[] present = {false};
                McaStream.read(path, new RegionStream.Consumer() {
                    @Override public void begin(int index, int timestamp) {
                        activeIndex[0] = index;
                        chunkBytes.reset();
                        present[0] = false;
                        capture[0] = sameFile && index == targetIndex
                                || !incoming.isEmpty() && !(sameFile && index == targetIndex);
                    }

                    @Override public void present(boolean value) { present[0] = value; }

                    @Override public void data(byte[] buffer, int offset, int length) throws IOException {
                        if (!capture[0]) return;
                        chunkBytes.write(buffer, offset, length);
                    }

                    @Override public void end() throws IOException {
                        if (!capture[0] || !present[0]) return;
                        byte[] raw = chunkBytes.toByteArray();
                        if (sameFile && activeIndex[0] == targetIndex) {
                            targetChunk[0] = raw;
                            return;
                        }
                        int chunkX = coords[0] * 32 + activeIndex[0] % 32;
                        int chunkZ = coords[1] * 32 + activeIndex[0] / 32;
                        var other = EntityRegionCodec.read(raw, chunkX, chunkZ);
                        for (UUID id : EntityRegionCodec.uuids(other)) {
                            if (incoming.contains(id)) {
                                conflicts.add(id);
                                issues.add("Entity UUID conflict outside target chunk: " + id + " in " + path);
                            }
                        }
                    }
                });
            }
        }
        return new EntityScan(Set.copyOf(conflicts), targetChunk[0]);
    }

    record EntityScan(Set<UUID> conflicts, byte[] targetChunk) {}

    private Path relativeTarget(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(dimensionDir)) throw new IOException("Rollback target is outside dimension: " + target);
        return dimensionDir.relativize(absolute);
    }

    void write(McaRegionUpdate blocks, McaRegionUpdate entities, int compression) throws IOException {
        try (var pending = Files.newDirectoryStream(dimensionDir, ".foldbackup-rollback-*.wip")) {
            for (Path path : pending) throw new IOException("Previous rollback files require inspection before retry: " + path);
        }
        // The normal backup filter excludes .wip directories, including recovery files left after a crash.
        Path stage = Files.createDirectory(dimensionDir.resolve(".foldbackup-rollback-" + UUID.randomUUID() + ".wip"));
        List<McaRegionUpdate> updates = entities == null ? List.of(blocks) : List.of(blocks, entities);
        List<Path> changed = new ArrayList<>();
        Map<Path, Boolean> existed = new LinkedHashMap<>();
        boolean preserve = false;
        boolean committed = false;
        try {
            for (McaRegionUpdate update : updates) {
                Path relative = relativeTarget(update.source());
                Path destination = stage.resolve("new").resolve(relative);
                McaStreamWriter.write(destination, update.regionX(), update.regionZ(), update.mtimeMillis(),
                        compression, update.stream());
            }

            List<Path> files;
            try (var paths = Files.walk(stage.resolve("new"))) {
                files = paths.filter(Files::isRegularFile).map(p -> stage.resolve("new").relativize(p))
                        .sorted(Comparator.<Path, Boolean>comparing(p -> p.toString().endsWith(".mca"))
                                .thenComparing(Path::toString)).toList();
            }
            Set<Path> stagedFiles = new HashSet<>(files);
            Set<Path> staleMcc = new TreeSet<>();
            for (McaRegionUpdate update : updates) {
                for (Path mcc : McaHasher.findMccFilesForRegion(update.source().getParent(),
                        update.regionX(), update.regionZ())) {
                    Path relative = relativeTarget(mcc);
                    if (!stagedFiles.contains(relative)) staleMcc.add(relative);
                }
            }

            // Retain original files before touching targets. The same journal covers installs and MCC removals.
            Set<Path> affected = new LinkedHashSet<>(files);
            affected.addAll(staleMcc);
            for (Path relative : affected) {
                Path target = dimensionDir.resolve(relative);
                if (!ChunkRollbackSupport.tryLockRegionFile(target))
                    throw new IOException("Target is locked: " + target);
                boolean present = Files.exists(target);
                existed.put(relative, present);
                if (present) {
                    Path old = stage.resolve("old").resolve(relative);
                    Files.createDirectories(old.getParent());
                    Files.copy(target, old, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }

            // Retained with original bytes if a crash interrupts the sequence or recovery fails.
            List<String> journal = new ArrayList<>();
            existed.forEach((path, present) -> journal.add((present ? "restore " : "remove ") + path));
            Files.write(stage.resolve("recovery.txt"), journal);
            for (Path relative : files) {
                Path target = dimensionDir.resolve(relative);
                Files.createDirectories(target.getParent());
                move.move(stage.resolve("new").resolve(relative), target);
                changed.add(relative);
            }
            for (Path relative : staleMcc) {
                Path target = dimensionDir.resolve(relative);
                if (!Files.exists(target)) continue;
                Path removed = stage.resolve("removed").resolve(relative);
                Files.createDirectories(removed.getParent());
                move.move(target, removed);
                changed.add(relative);
            }
            Files.writeString(stage.resolve("COMMITTED"), "All staged files were installed.\n");
            committed = true;
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(changed);
            for (Path relative : changed) {
                try {
                    Path target = dimensionDir.resolve(relative);
                    if (existed.get(relative)) Files.move(stage.resolve("old").resolve(relative), target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    else Files.deleteIfExists(target);
                } catch (IOException recovery) { failure.addSuppressed(recovery); preserve = true; }
            }
            throw new IOException("Offline rollback failed; written files=" + changed
                    + (preserve ? "; recovery incomplete, retained files at " + stage : "; previous files restored"), failure);
        } finally {
            if (!preserve) {
                try (var paths = Files.walk(stage)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                } catch (IOException cleanup) {
                    throw new IOException((committed ? "Rollback committed; " : "Rollback recovery finished; ")
                            + "temporary cleanup failed at " + stage, cleanup);
                }
            }
        }
    }

    private boolean isEntityRegion(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")
                || !path.getParent().getFileName().toString().equals("entities")) return false;
        Path relative = worldRoot.relativize(path);
        int count = relative.getNameCount();
        String first = relative.getName(0).toString();
        return (count == 2 && first.equals("entities"))
                || (count == 3 && Set.of("DIM-1", "DIM1").contains(first))
                || (count >= 5 && first.equals("dimensions") && !relative.toString().contains(".foldbackup-rollback-"));
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
