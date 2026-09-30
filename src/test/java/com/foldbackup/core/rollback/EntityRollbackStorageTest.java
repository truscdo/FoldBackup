package com.foldbackup.core.rollback;

import com.foldbackup.core.BackupExporter;
import com.foldbackup.core.backup.RegionProcessor;
import com.foldbackup.core.archive.ArchiveCreator;
import com.foldbackup.core.archive.ArchiveRestorer;
import com.foldbackup.core.format.*;
import com.foldbackup.core.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static com.foldbackup.core.format.EntityNbtFixture.*;

class EntityRollbackStorageTest {
    @TempDir Path temp;
    private SnapshotStore snapshots;
    private BlobStore blobs;
    private int sequence;

    private SnapshotManifest backup(Path world) throws Exception {
        snapshots = new SnapshotStore(temp.resolve("store"));
        blobs = new BlobStore(temp.resolve("store"), 6);
        var scan = new RegionProcessor(snapshots::getIndex, blobs, 6).scanWorld(world, null, null);
        assertTrue(scan.getErrors().isEmpty(), scan.getErrors().toString());
        var snapshot = new SnapshotManifest("S-20260908_12000" + sequence, 1788868800L + sequence++, "world");
        snapshot.setRegions(scan.getRegions()); snapshot.setExtraFiles(scan.getFiles());
        snapshots.saveSnapshot(snapshot);
        return snapshot;
    }
    private void write(Path file, byte[] target, byte[] neighbor) throws Exception {
        Region r = new Region(0, 0, 1000);
        if (target != null) r.setChunk(0, new ChunkData(target, 0, 0));
        if (neighbor != null) r.setChunk(1, new ChunkData(neighbor, 1, 0));
        com.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(file, r, 6);
    }
    private byte[] read(Path path, int index) throws Exception {
        var c = AnvilTestSupport.readRegionAnvil(path).getChunk(index);
        return c == null ? null : c.getRawChunk();
    }

    @Test void failedSecondFileCommitRestoresFirstFile() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        write(blocks, new byte[]{1}, null); write(entities, chunk(0, 0), null);
        byte[] oldBlocks = Files.readAllBytes(blocks), oldEntities = Files.readAllBytes(entities);
        var next = new McaRegionUpdate(blocks, 0, 0, 1000, 0, new byte[]{2}, 0);
        var nextEntities = new McaRegionUpdate(entities, 0, 0, 1000, 0, new byte[]{2}, 0);
        int[] moves = {0};
        try (var writer = new OfflineRollbackWriter(blocks.getParent(), (source, target) -> {
            if (++moves[0] == 2) throw new java.io.IOException("injected second commit failure");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            var failure = assertThrows(java.io.IOException.class, () -> writer.write(next, nextEntities, 6));
            assertTrue(failure.getMessage().contains("previous files restored"));
        }
        assertEquals(2, moves[0]);
        assertArrayEquals(oldBlocks, Files.readAllBytes(blocks));
        assertArrayEquals(oldEntities, Files.readAllBytes(entities));
    }

    @Test void streamingRewritePreservesExternalNeighborAndRemovesStaleMcc() throws Exception {
        Path world = temp.resolve("world"), regionDir = world.resolve("region"), mca = regionDir.resolve("r.0.0.mca");
        byte[] neighbor = new byte[2 << 20]; new Random(42).nextBytes(neighbor);
        Region original = new Region(0, 0, 1000);
        original.setChunk(1, new ChunkData(neighbor, 1, 0)); original.setTimestamp(1, 77);
        McaStreamWriter.write(mca, 0, 0, 1000, 6, RegionStream.of(original));
        Path activeMcc = regionDir.resolve("c.1.0.mcc");
        Path staleMcc = regionDir.resolve("c.2.0.mcc");
        assertTrue(Files.exists(activeMcc));
        Files.write(staleMcc, new byte[]{4, 5, 6});

        var update = new McaRegionUpdate(mca, 0, 0, 1000, 0, new byte[]{9}, 99);
        try (var writer = new OfflineRollbackWriter(regionDir)) { writer.write(update, null, 6); }

        Region rewritten = AnvilTestSupport.readRegionAnvil(mca);
        assertArrayEquals(new byte[]{9}, rewritten.getChunk(0).getRawChunk());
        assertEquals(99, rewritten.getTimestamp(0));
        assertArrayEquals(neighbor, rewritten.getChunk(1).getRawChunk());
        assertEquals(77, rewritten.getTimestamp(1));
        assertTrue(Files.exists(activeMcc));
        assertFalse(Files.exists(staleMcc));
    }

    @Test void streamingRewriteCreatesFullRegionFromMissingOrZeroByteMca() throws Exception {
        Path regionDir = temp.resolve("world/region"), mca = regionDir.resolve("r.0.0.mca");
        Files.createDirectories(regionDir);
        for (boolean zeroByteSource : List.of(true, false)) {
            Files.deleteIfExists(mca);
            if (zeroByteSource) Files.createFile(mca);
            var update = new McaRegionUpdate(mca, 0, 0, McaRegionUpdate.mtime(mca), 35,
                    new byte[]{7, 8, 9}, 321);
            try (var writer = new OfflineRollbackWriter(regionDir)) { writer.write(update, null, 6); }
            var summary = RegionStream.inspect(McaStream.source(mca));
            assertEquals(3, summary.lengths()[35]);
            assertEquals(321, summary.timestamps()[35]);
            for (int i = 0; i < 1024; i++) {
                if (i != 35) assertEquals(0, summary.lengths()[i]);
            }
            assertArrayEquals(new byte[]{7, 8, 9}, read(mca, 35));
        }
    }

    @Test void failedStaleMccRemovalRestoresTheRegionAndSidecar() throws Exception {
        Path world = temp.resolve("world"), regionDir = world.resolve("region"), mca = regionDir.resolve("r.0.0.mca");
        write(mca, new byte[]{1}, null);
        byte[] originalMca = Files.readAllBytes(mca);
        Path staleMcc = regionDir.resolve("c.1.0.mcc");
        byte[] originalMcc = {4, 5, 6}; Files.write(staleMcc, originalMcc);
        var update = new McaRegionUpdate(mca, 0, 0, 1000, 0, new byte[]{2}, 99);
        int[] moves = {0};
        try (var writer = new OfflineRollbackWriter(regionDir, (source, target) -> {
            if (++moves[0] == 2) throw new java.io.IOException("injected MCC removal failure");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            var failure = assertThrows(java.io.IOException.class, () -> writer.write(update, null, 6));
            assertTrue(failure.getMessage().contains("previous files restored"));
        }
        assertEquals(2, moves[0]);
        assertArrayEquals(originalMca, Files.readAllBytes(mca));
        assertArrayEquals(originalMcc, Files.readAllBytes(staleMcc));
    }

    @Test void backupExportAndArchiveRestorePreserveAllDimensionPaths() throws Exception {
        Path world = temp.resolve("world");
        List<String> paths = List.of("region/r.0.0.mca", "entities/r.0.0.mca", "DIM-1/entities/r.0.0.mca",
                "DIM1/entities/r.0.0.mca", "dimensions/test/moon/entities/r.0.0.mca");
        Map<String, byte[]> expected = new HashMap<>();
        for (String path : paths) {
            byte[] data = chunk(0, 0, entity(UUID.randomUUID(), 8, 8));
            expected.put(path, data); write(world.resolve(path), data, null);
        }
        var snapshot = backup(world);
        assertEquals(Set.copyOf(paths), snapshot.getRegions().stream().map(RegionEntry::getFilename).collect(Collectors.toSet()));
        Path exported = temp.resolve("exported"), linear = temp.resolve("linear"), archive = temp.resolve("archive"), restored = temp.resolve("restored");
        var exporter = new BackupExporter(snapshots, blobs, 6);
        exporter.export(snapshot.getEpochSeconds(), exported, BackupExporter.Format.MCA, false);
        exporter.export(snapshot.getEpochSeconds(), linear, BackupExporter.Format.LINEAR, false);
        new ArchiveCreator(snapshots, blobs, 6).createFull(snapshot.getEpochSeconds(), archive);
        new ArchiveRestorer(6).restore(archive, null, null, restored);
        for (String path : paths) {
            assertArrayEquals(expected.get(path), read(exported.resolve(path), 0));
            assertArrayEquals(expected.get(path), read(restored.resolve(path), 0));
            assertArrayEquals(expected.get(path), com.foldbackup.core.format.LrhTestSupport.openRegionLinear(Files.readAllBytes(linear.resolve(path.replace(".mca", ".lrh"))), 0, 0, 0).getChunk(0).getRawChunk());
        }
    }

    @Test void offlineReplacesBothSlotsAndKeepsNeighbors() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        byte[] oldEntity = chunk(0, 0, entity(UUID.randomUUID(), 8, 8));
        write(blocks, new byte[]{1}, null); write(entities, oldEntity, null);
        var snapshot = backup(world);
        byte[] neighbor = chunk(1, 0, entity(UUID.randomUUID(), 24, 8));
        write(blocks, new byte[]{2}, new byte[]{3}); write(entities, chunk(0, 0), neighbor);
        // A region opened by vanilla but never written is a valid empty file.
        Files.createFile(entities.resolveSibling("r.1.0.mca"));
        // A copy inside a backup folder is not a live dimension and must not create a UUID conflict.
        write(world.resolve("backups/entities/r.0.0.mca"), oldEntity, null);
        var result = new ChunkRollbackCoordinator(snapshots, blobs, 6).rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "");
        assertTrue(result.success(), result.message());
        assertArrayEquals(new byte[]{1}, read(blocks, 0)); assertArrayEquals(new byte[]{3}, read(blocks, 1));
        assertArrayEquals(oldEntity, read(entities, 0)); assertArrayEquals(neighbor, read(entities, 1));
    }

    @Test void outsideUuidConflictAndMalformedSnapshotStillRestoreBlocks() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        UUID id = UUID.randomUUID();
        write(blocks, new byte[]{1}, null); write(entities, chunk(0, 0, entity(id, 8, 8)), null);
        var snapshot = backup(world);
        write(blocks, new byte[]{2}, null); write(entities, null, chunk(1, 0, entity(id, 24, 8)));
        byte[] beforeEntities;
        var result = new ChunkRollbackCoordinator(snapshots, blobs, 6).rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "");
        assertTrue(result.success()); assertTrue(result.message().contains("UUID conflict"), result.message());
        assertArrayEquals(new byte[]{1}, read(blocks, 0));
        assertNull(read(entities, 0));
        assertEquals(Set.of(id), EntityRegionCodec.uuids(EntityRegionCodec.read(read(entities, 1), 1, 0)));
        write(entities, new byte[]{10, 0}, null);
        var malformed = backup(world);
        write(blocks, new byte[]{3}, null);
        write(entities, chunk(0, 0), null);
        beforeEntities = Files.readAllBytes(entities);
        result = new ChunkRollbackCoordinator(snapshots, blobs, 6).rollbackChunk(blocks.getParent(), 0, 0, malformed.getEpochSeconds(), "");
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("current entity files preserved"));
        assertArrayEquals(new byte[]{1}, read(blocks, 0)); assertArrayEquals(beforeEntities, Files.readAllBytes(entities));
    }

    @Test void conflictPreservesWholeCurrentTreeAndRestoresIndependentEntity() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        UUID root = UUID.randomUUID(), passenger = UUID.randomUUID(), valid = UUID.randomUUID(), currentPassenger = UUID.randomUUID();
        write(blocks, new byte[]{1}, null);
        write(entities, chunk(0, 0, entity(root, 8, 8, "minecraft:pig", 11, "", entity(passenger, 8, 8)),
                entity(valid, 8, 8), entity(currentPassenger, 8, 8)), null);
        var snapshot = backup(world);
        byte[] currentTree = entity(root, 9, 8, "minecraft:pig", 11, "", entity(currentPassenger, 9, 8));
        write(entities, chunk(0, 0, currentTree), null);
        Path other = world.resolve("DIM-1/entities/r.0.0.mca");
        write(other, chunk(0, 0, entity(passenger, 8, 8)), null);
        byte[] otherBefore = Files.readAllBytes(other);
        var result = new ChunkRollbackCoordinator(snapshots, blobs, 6).rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "");
        assertTrue(result.success(), result.message());
        var restored = EntityRegionCodec.read(read(entities, 0), 0, 0);
        assertEquals(Set.of(root, currentPassenger, valid), EntityRegionCodec.uuids(restored));
        assertEquals(9, restored.entities().stream().filter(t -> t.uuid().equals(root)).findFirst().orElseThrow().x());
        assertArrayEquals(otherBefore, Files.readAllBytes(other));
        assertTrue(result.message().contains("skipped trees=2"), result.message());
    }

    @Test void unreadableNeighborPreservesEntityFileBytesButRestoresBlocks() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        write(blocks, new byte[]{1}, null);
        write(entities, chunk(0, 0, entity(UUID.randomUUID(), 8, 8)), null);
        var snapshot = backup(world);
        write(blocks, new byte[]{2}, null);
        write(entities, chunk(0, 0, entity(UUID.randomUUID(), 8, 8)), new byte[0]);
        byte[] before = Files.readAllBytes(entities);
        var result = new ChunkRollbackCoordinator(snapshots, blobs, 6).rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "");
        assertTrue(result.success(), result.message());
        assertArrayEquals(new byte[]{1}, read(blocks, 0));
        assertArrayEquals(before, Files.readAllBytes(entities));
        assertTrue(result.message().contains("current entity files preserved"));
    }

    @Test void missingRegionOrChunkMeansEmptyAndLocksPreventAnyWrites() throws Exception {
        Path world = temp.resolve("world"), blocks = world.resolve("region/r.0.0.mca"), entities = world.resolve("entities/r.0.0.mca");
        write(blocks, new byte[]{1}, null);
        var snapshot = backup(world);
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.REGION_MISSING,
                ChunkRollbackSupport.resolveEntities(snapshot, blobs, 0, 0, "").state());
        write(entities, chunk(0, 0, entity(UUID.randomUUID(), 8, 8)), null);
        byte[] before = Files.readAllBytes(entities);
        var coordinator = new ChunkRollbackCoordinator(snapshots, blobs, 6);
        for (Path lockPath : List.of(world.resolve("session.lock"), entities, blocks)) {
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                var result = coordinator.rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "");
                assertFalse(result.success(), result.message());
            }
            assertArrayEquals(before, Files.readAllBytes(entities));
        }
        assertTrue(coordinator.rollbackChunk(blocks.getParent(), 0, 0, snapshot.getEpochSeconds(), "").success());
        assertNull(read(entities, 0));
        var second = backup(world);
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.CHUNK_MISSING,
                ChunkRollbackSupport.resolveEntities(second, blobs, 0, 0, "").state());
    }
}
