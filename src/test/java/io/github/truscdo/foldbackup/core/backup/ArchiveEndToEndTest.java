package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;
import io.github.truscdo.foldbackup.core.archive.ArchiveImporter;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestorer;
import io.github.truscdo.foldbackup.core.archive.ArchiveVerifier;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end archive flow: online backup → archive create (full + diff)
 * → archive export (full, full+diff) → archive verify → snapshot import --all-archive.
 */
class ArchiveEndToEndTest {

    @TempDir
    Path tempDir;

    // ── helpers ──

    private static byte[] nbt(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path createWorld(Path root, String name) throws IOException {
        Path world = root.resolve(name);
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));

        // region r.0.0: chunks (0,0) and (1,0)
        Region r00 = new Region(0, 0, 1000);
        r00.setChunk(0, new ChunkData(nbt("chunk-0-0-v1"), 0, 0));
        r00.setTimestamp(0, 1000);
        r00.setChunk(1, new ChunkData(nbt("chunk-1-0-v1"), 1, 0));
        r00.setTimestamp(1, 1000);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), r00, 6);

        // region r.0.1: chunk (0,32)
        Region r01 = new Region(0, 1, 1000);
        r01.setChunk(0, new ChunkData(nbt("chunk-0-32-v1"), 0, 32));
        r01.setTimestamp(0, 1000);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.1.mca"), r01, 6);

        Files.writeString(world.resolve("level.dat"), "level-v1");
        Files.writeString(world.resolve("playerdata/a.dat"), "player-a-v1");
        return world;
    }

    private void mutateWorld(Path world) throws IOException {
        // Modify chunk (0,0), delete chunk (1,0), keep chunk (0,32)
        Region r00 = AnvilTestSupport.readRegionAnvil(world.resolve("region/r.0.0.mca"));
        r00.setChunk(0, new ChunkData(nbt("chunk-0-0-v2"), 0, 0));
        r00.setTimestamp(0, 2000);
        r00.setChunk(1, null);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), r00, 6);

        Files.writeString(world.resolve("level.dat"), "level-v2");
        Files.writeString(world.resolve("playerdata/b.dat"), "player-b-new");
        Files.deleteIfExists(world.resolve("playerdata/a.dat"));
    }

    private SnapshotManifest backup(Path world, Path backupRoot, String id, long time,
            SnapshotManifest latestSnapshot) throws IOException {
        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var processor = new RegionProcessor(snapshotManager::getIndex, blobStore, 6);
        ScanResult result = processor.scanWorld(world, latestSnapshot, null);

        SnapshotManifest manifest = new SnapshotManifest(id, time, "world");
        manifest.setRegions(result.getRegions());
        manifest.setExtraFiles(result.getFiles());
        snapshotManager.saveSnapshot(manifest);
        return manifest;
    }

    private byte[] chunkNbt(Path world, String regionFile, int gridIdx) throws IOException {
        Region r = AnvilTestSupport.readRegionAnvil(world.resolve(regionFile));
        ChunkData c = r.getChunk(gridIdx);
        return c != null ? c.getRawChunk() : null;
    }

    private long lrhdChunkDataBytes(Path tar, String name) throws IOException {
        byte[] lrhd = readTarEntry(tar, name);
        io.github.truscdo.foldbackup.core.format.LrhdTestSupport.LrhdFile f = io.github.truscdo.foldbackup.core.format.LrhdTestSupport.readEntry(lrhd);
        long total = 0;
        for (io.github.truscdo.foldbackup.core.format.LrhdFormat.ChunkIndex ci : f.allChunks()) {
            if (!ci.deleted) {
                total += ci.length;
            }
        }
        return total;
    }

    /** Total NBT bytes of chunks in a .lrh entry. */
    private long lrhChunkDataBytes(Path tar, String name) throws IOException {
        byte[] lrh = readTarEntry(tar, name);
        Region region = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(lrh, 0, 0, 0);
        long total = 0;
        for (int i = 0; i < 1024; i++) {
            ChunkData c = region.getChunk(i);
            if (c != null) {
                total += c.getRawChunk().length;
            }
        }
        return total;
    }

    private byte[] readTarEntry(Path tar, String name) throws IOException {
        try (var in = Files.newInputStream(tar);
                io.github.truscdo.foldbackup.core.archive.TarReader reader = new io.github.truscdo.foldbackup.core.archive.TarReader(in)) {
            io.github.truscdo.foldbackup.core.archive.TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) {
                if (e.name.equals(name)) {
                    return reader.readEntryData();
                }
                reader.readEntryData();
            }
        }
        throw new IOException("Entry not found: " + name);
    }

    // ── tests ──

    @Test
    void fullFlow_exportRestoreVerifyImport() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");

        // ── online backups ──
        SnapshotManifest sn1 = backup(world, backupRoot, "S-20260822_120000", 1724311200L, null);
        mutateWorld(world);
        SnapshotManifest sn2 = backup(world, backupRoot, "S-20260822_130000", 1724313600L,
                sn1);

        // ── archive create full ──
        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, 19);
        Path fullTar = exporter.createFull(sn1.getEpochSeconds(), archiveRoot);
        assertTrue(Files.isRegularFile(fullTar), "full tar written");
        assertEquals("S-20260822_120000[full].tar", fullTar.getFileName().toString());
        var fullMeta = io.github.truscdo.foldbackup.core.archive.ArchiveSnapshotCodec.decode(
                readTarEntry(fullTar, "snapshot.bin"));
        assertEquals(sn1.getEpochSeconds(), fullMeta.epoch,
                "archive full epoch must use the online snapshot's Unix epoch seconds");

        // ── archive create diff ──
        var progressEvents = new java.util.ArrayList<io.github.truscdo.foldbackup.core.ProgressEvent>();
        Path diffTar = exporter.createDiffWithProgress(sn1.getEpochSeconds(), sn2.getEpochSeconds(), archiveRoot,
                progressEvents::add);
        for (String phase : List.of("Reading metadata", "Comparing regions",
                "Comparing file manifests", "Writing archive", "Finalizing archive")) {
            var stage = progressEvents.stream().filter(e -> e.phase().equals(phase)).toList();
            assertFalse(stage.isEmpty(), phase);
            assertEquals(0, stage.getFirst().completed(), phase);
            assertEquals(stage.getLast().total(), stage.getLast().completed(), phase);
            for (int i = 1; i < stage.size(); i++) {
                assertTrue(stage.get(i).completed() >= stage.get(i - 1).completed(), phase);
            }
        }
        assertTrue(Files.isRegularFile(diffTar), "diff tar written");
        assertEquals("S-20260822_120000[S-20260822_130000].tar", diffTar.getFileName().toString());
        var diffMeta = io.github.truscdo.foldbackup.core.archive.ArchiveSnapshotCodec.decode(
                readTarEntry(diffTar, "snapshot.bin"));
        assertEquals(sn2.getEpochSeconds(), diffMeta.epoch,
                "archive diff epoch must use Unix epoch seconds");
        assertEquals(sn1.getEpochSeconds(), diffMeta.baseEpoch,
                "archive diff base epoch must use the base snapshot's Unix epoch seconds");
        reverseRegions(fullTar);
        // Chunk-level diff must carry far less chunk data than the full .lrh set:
        // diff.lrhd holds 1 changed chunk + 1 tombstone, full holds 3 chunks.
        long lrhdChunkBytes = lrhdChunkDataBytes(diffTar, "region/diff.lrhd");
        long lrhChunkBytes = fullMeta.regions.stream().mapToLong(region -> {
            try { return lrhChunkDataBytes(fullTar, ArchiveCreator.regionObjectPath(region.contentHash)); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }).sum();
        assertTrue(lrhdChunkBytes < lrhChunkBytes,
                "diff.lrhd chunk data (" + lrhdChunkBytes + ") < full .lrh chunk data (" + lrhChunkBytes + ")");

        // Archive names are presentation labels. Core restore, verify, and import
        // must continue to locate archives from snapshot.bin Epoch metadata.
        Path renamedFullTar = archiveRoot.resolve("renamed-full.tar");
        Path renamedDiffTar = archiveRoot.resolve("renamed-diff.tar");
        Files.move(fullTar, renamedFullTar);
        Files.move(diffTar, renamedDiffTar);

        // ── archive export full only ──
        Path outFull = tempDir.resolve("restore-full");
        var restorer = new ArchiveRestorer(6);
        restorer.restore(archiveRoot, null, null, outFull);
        assertArrayEquals(nbt("chunk-0-0-v1"), chunkNbt(outFull, "region/r.0.0.mca", 0));
        assertArrayEquals(nbt("chunk-1-0-v1"), chunkNbt(outFull, "region/r.0.0.mca", 1));
        assertArrayEquals(nbt("chunk-0-32-v1"), chunkNbt(outFull, "region/r.0.1.mca", 0));
        assertEquals("level-v1", Files.readString(outFull.resolve("level.dat")));
        assertEquals("player-a-v1", Files.readString(outFull.resolve("playerdata/a.dat")));

        // ── archive export full + diff ──
        Path outDiff = tempDir.resolve("restore-diff");
        restorer.restore(archiveRoot, sn1.getEpochSeconds(), sn2.getEpochSeconds(), outDiff);
        assertArrayEquals(nbt("chunk-0-0-v2"), chunkNbt(outDiff, "region/r.0.0.mca", 0),
                "modified chunk applied");
        assertNull(chunkNbt(outDiff, "region/r.0.0.mca", 1), "deleted chunk removed");
        assertArrayEquals(nbt("chunk-0-32-v1"), chunkNbt(outDiff, "region/r.0.1.mca", 0),
                "unchanged chunk inherited from full");
        assertEquals("level-v2", Files.readString(outDiff.resolve("level.dat")));
        assertEquals("player-b-new", Files.readString(outDiff.resolve("playerdata/b.dat")));
        assertFalse(Files.exists(outDiff.resolve("playerdata/a.dat")), "deleted file removed");

        // ── archive verify ──
        var verifier = new ArchiveVerifier();
        ArchiveVerifier.Result vr = verifier.verify(archiveRoot, sn1.getEpochSeconds(), sn2.getEpochSeconds());
        assertTrue(vr.ok(), "verify errors: " + vr.errors);
        assertTrue(vr.lrhChecked >= 2);
        assertTrue(vr.lrhdChecked >= 1);

        // ── snapshot import --all-archive (disaster recovery) ──
        Path newBackupRoot = tempDir.resolve("backups-rebuilt");
        var importer = new ArchiveImporter(archiveRoot, newBackupRoot);
        int imported = importer.importAll();
        assertEquals(2, imported, "full + diff imported");

        var rebuiltManager = new SnapshotStore(newBackupRoot);
        SnapshotManifest rebuilt = rebuiltManager.loadSnapshot(sn1.getEpochSeconds());
        assertNotNull(rebuilt, "imported full snapshot exists");
        assertEquals(SnapshotStore.generateSnapshotId(sn1.getEpochSeconds()), rebuilt.getSnapshotId());
        assertEquals(sn1.getEpochSeconds(), rebuilt.getEpochSeconds(), "import must preserve snapshot time");
        assertEquals("world", rebuilt.getWorldName());
        assertEquals(2, rebuilt.getRegions().size());
        assertEquals(fullMeta.regions.stream().map(r -> r.filename).toList(),
                rebuilt.getRegions().stream().map(RegionEntry::getFilename).toList());
        assertEquals(2, rebuilt.getExtraFiles().size());

        // Differential archives are restored as independent online manifests.
        SnapshotManifest rebuiltDiff = rebuiltManager.loadSnapshot(sn2.getEpochSeconds());
        assertNotNull(rebuiltDiff, "imported diff snapshot exists");
        assertEquals(SnapshotStore.generateSnapshotId(sn2.getEpochSeconds()), rebuiltDiff.getSnapshotId());
        assertEquals(sn2.getEpochSeconds(), rebuiltDiff.getEpochSeconds(), "import must preserve diff snapshot time");
        assertEquals(2, rebuiltDiff.getRegions().size());
        assertEquals(diffMeta.regions.stream().map(r -> r.filename).toList(),
                rebuiltDiff.getRegions().stream().map(RegionEntry::getFilename).toList());
        assertEquals(2, rebuiltDiff.getExtraFiles().size());

        // Imported region content must round-trip to the same chunks
        var rebuiltBlobStore = new BlobStore(newBackupRoot, 6);
        for (RegionEntry re : rebuilt.getRegions()) {
            byte[] lrh = rebuiltBlobStore.readLrh(re.getBlobPath());
            assertArrayEquals(readTarEntry(renamedFullTar, ArchiveCreator.regionObjectPath(re.getContentHash())), lrh);
            assertEquals(re.getContentHash(), io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                    io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.McaStream.source(
                            outFull.resolve(re.getFilename()))).hash()));
            Region region = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(lrh, 0, 0, 0);
            assertTrue(region.chunkCount() > 0, "imported region has chunks");
        }

        // Diff snapshot must reflect the merged state (chunk-0-0-v2, no chunk-1-0)
        for (RegionEntry re : rebuiltDiff.getRegions()) {
            assertEquals(re.getContentHash(), io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                    io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.McaStream.source(
                            outDiff.resolve(re.getFilename()))).hash()));
            byte[] lrh = rebuiltBlobStore.readLrh(re.getBlobPath());
            Region region = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(lrh, 0, 0, 0);
            if (re.getFilename().equals("region/r.0.0.mca")) {
                assertArrayEquals(nbt("chunk-0-0-v2"), region.getChunk(0).getRawChunk(),
                        "diff snapshot has modified chunk");
                assertNull(region.getChunk(1), "diff snapshot has deleted chunk removed");
            }
        }
    }

    private void reverseRegions(Path archive) throws IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (var reader = new io.github.truscdo.foldbackup.core.archive.TarReader(Files.newInputStream(archive))) {
            io.github.truscdo.foldbackup.core.archive.TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) entries.put(e.name, reader.readEntryData());
        }
        var metadata = io.github.truscdo.foldbackup.core.archive.ArchiveSnapshotCodec.decode(entries.get("snapshot.bin"));
        var regions = new java.util.ArrayList<>(metadata.regions);
        java.util.Collections.reverse(regions);
        try (var writer = new io.github.truscdo.foldbackup.core.archive.TarWriter(Files.newOutputStream(archive))) {
            writer.putEntry("snapshot.bin", entries.remove("snapshot.bin"));
            var written = new java.util.HashSet<String>();
            for (var region : regions) {
                String object = ArchiveCreator.regionObjectPath(region.contentHash);
                if (written.add(object) && entries.containsKey(object)) writer.putEntry(object, entries.remove(object));
            }
            for (var entry : entries.entrySet()) writer.putEntry(entry.getKey(), entry.getValue());
        }
    }

    private void writeRegion(Path world, String dir, String data) throws IOException {
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData(nbt(data), 0, 0));
        region.setTimestamp(0, 1000);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve(dir + "/r.0.0.mca"), region, 6);
    }

    @Test
    void parallelExternalChunksAndFusedDiffMatchSerialRestoration() throws Exception {
        Path world = createWorld(tempDir, "external-world");
        Path backupRoot = tempDir.resolve("external-backups");
        byte[] data = new byte[1200000];
        new java.util.Random(42).nextBytes(data);
        Region large = new Region(0, 0, 0);
        large.setChunk(0, new ChunkData(data, 0, 0));
        large.setTimestamp(0, 12345);
        io.github.truscdo.foldbackup.core.format.McaStreamWriter.write(world.resolve("region/r.0.0.mca"), 0, 0, 0, 6,
                io.github.truscdo.foldbackup.core.format.RegionStream.of(large));
        assertTrue(Files.exists(world.resolve("region/c.0.0.mcc")));
        var full = backup(world, backupRoot, "S-20260822_120000", 1724311200L, null);
        data[0] ^= 1;
        large.setTimestamp(0, 54321);
        io.github.truscdo.foldbackup.core.format.McaStreamWriter.write(world.resolve("region/r.0.0.mca"), 0, 0, 0, 6,
                io.github.truscdo.foldbackup.core.format.RegionStream.of(large));
        var diff = backup(world, backupRoot, "S-20260822_130000", 1724314800L, full);
        Path archive = tempDir.resolve("external-archive");
        var exporter = new ArchiveCreator(new SnapshotStore(backupRoot), new BlobStore(backupRoot, 6), 19);
        reverseRegions(exporter.createFull(full.getEpochSeconds(), archive));
        exporter.createDiff(full.getEpochSeconds(), diff.getEpochSeconds(), archive);
        Path serial = tempDir.resolve("serial"), parallel = tempDir.resolve("parallel");
        new ArchiveRestorer(6, 1).restore(archive, full.getEpochSeconds(), diff.getEpochSeconds(), serial);
        new ArchiveRestorer(6, 4).restore(archive, full.getEpochSeconds(), diff.getEpochSeconds(), parallel);
        try (var walk = Files.walk(serial)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path other = parallel.resolve(serial.relativize(file));
                assertEquals(-1L, Files.mismatch(file, other));
                if (file.toString().endsWith(".mca") || file.toString().endsWith(".mcc"))
                    assertEquals(Files.getLastModifiedTime(file), Files.getLastModifiedTime(other));
            }
        }
        Path rebuilt = tempDir.resolve("external-import");
        assertEquals(2, new ArchiveImporter(archive, rebuilt, 4).importAll());
        var imported = new SnapshotStore(rebuilt).loadSnapshot(diff.getEpochSeconds());
        for (var region : imported.getRegions()) {
            Path mca = parallel.resolve(region.getFilename());
            assertEquals(region.getContentHash(), io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                    io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.McaStream.source(mca)).hash()));
            var expected = io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.McaStream.source(mca));
            var actual = io.github.truscdo.foldbackup.core.format.LrhFormat.read(rebuilt.resolve(region.getBlobPath()),
                    new io.github.truscdo.foldbackup.core.format.RegionStream.Accumulator());
            assertTrue(expected.same(actual), region.getFilename());
        }
    }

    private Path exportAndRestore(Path backupRoot, long baseEpoch, long targetEpoch) throws IOException {
        Path archive = tempDir.resolve("archive");
        var exporter = new ArchiveCreator(new SnapshotStore(backupRoot), new BlobStore(backupRoot, 6), 19);
        exporter.createFull(baseEpoch, archive, false, 1);
        exporter.createDiff(baseEpoch, targetEpoch, archive);
        Path out = tempDir.resolve("restored");
        new ArchiveRestorer(6).restore(archive, baseEpoch, targetEpoch, out);
        return out;
    }

    @Test
    void diffKeepsSameCoordinatesInDifferentDirectoriesIndependent() throws Exception {
        Path world = tempDir.resolve("world");
        Path root = tempDir.resolve("backups");
        String[] dirs = {"region", "DIM-1/region", "entities", "poi"};
        for (int i = 0; i < dirs.length; i++) {
            writeRegion(world, dirs[i], "data-" + i);
        }
        SnapshotManifest base = backup(world, root, "S-20260822_120000", 1724311200L, null);
        // Rotate contents: comparing with any one directory's baseline must
        // incorrectly omit at least one modification when directory keys collide.
        for (int i = 0; i < dirs.length; i++) {
            writeRegion(world, dirs[i], "data-" + ((i + 1) % dirs.length));
            Files.setLastModifiedTime(world.resolve(dirs[i]).resolve("r.0.0.mca"),
                    java.nio.file.attribute.FileTime.fromMillis(2000 + i));
        }
        SnapshotManifest target = backup(world, root, "S-20260822_130000", 1724313600L, base);
        Path out = exportAndRestore(root, base.getEpochSeconds(), target.getEpochSeconds());
        for (int i = 0; i < dirs.length; i++) {
            assertArrayEquals(nbt("data-" + ((i + 1) % dirs.length)),
                    chunkNbt(out, dirs[i] + "/r.0.0.mca", 0), dirs[i]);
        }
    }

    @Test
    void diffRestoresNewDirectoriesAtExistingCoordinates() throws Exception {
        Path world = tempDir.resolve("world");
        Path root = tempDir.resolve("backups");
        writeRegion(world, "region", "overworld");
        SnapshotManifest base = backup(world, root, "S-20260822_120000", 1724311200L, null);
        writeRegion(world, "DIM-1/region", "nether");
        writeRegion(world, "entities", "entities");
        SnapshotManifest target = backup(world, root, "S-20260822_130000", 1724313600L, base);
        Path out = exportAndRestore(root, base.getEpochSeconds(), target.getEpochSeconds());
        assertArrayEquals(nbt("overworld"), chunkNbt(out, "region/r.0.0.mca", 0));
        assertArrayEquals(nbt("nether"), chunkNbt(out, "DIM-1/region/r.0.0.mca", 0));
        assertArrayEquals(nbt("entities"), chunkNbt(out, "entities/r.0.0.mca", 0));
    }

    @Test
    void diffRestoresNewEmptyRegionWithoutAnLrhdFrame() throws Exception {
        Path world = tempDir.resolve("empty-region-world");
        Path root = tempDir.resolve("empty-region-backups");
        Files.createDirectories(world.resolve("region"));
        SnapshotManifest base = backup(world, root, "S-20260822_120000", 1724311200L, null);
        Path mca = world.resolve("region/r.0.0.mca");
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca,
                new io.github.truscdo.foldbackup.core.format.Region(0, 0, 1000), 6);
        SnapshotManifest target = backup(world, root, "S-20260822_130000", 1724313600L, base);

        Path archive = tempDir.resolve("empty-region-archive");
        var exporter = new ArchiveCreator(new SnapshotStore(root), new BlobStore(root, 6), 19);
        exporter.createFull(base.getEpochSeconds(), archive, false, 1);
        exporter.createDiff(base.getEpochSeconds(), target.getEpochSeconds(), archive);
        Path restored = tempDir.resolve("empty-region-restored");
        new ArchiveRestorer(6, 2).restore(archive, base.getEpochSeconds(), target.getEpochSeconds(), restored);
        assertTrue(Files.isRegularFile(restored.resolve("region/r.0.0.mca")));
        assertNull(chunkNbt(restored, "region/r.0.0.mca", 0));

        Path rebuilt = tempDir.resolve("empty-region-imported");
        assertEquals(2, new ArchiveImporter(archive, rebuilt, 2).importAll());
        var imported = new SnapshotStore(rebuilt).loadSnapshot(target.getEpochSeconds());
        assertEquals(target.getRegions().getFirst().getContentHash(), imported.getRegions().getFirst().getContentHash());
    }

    @Test
    void diffDeletesWholeRegionsIncludingAnEmptyTargetSnapshot() throws Exception {
        Path world = tempDir.resolve("world");
        Path root = tempDir.resolve("backups");
        writeRegion(world, "region", "overworld");
        writeRegion(world, "DIM-1/region", "nether");
        SnapshotManifest base = backup(world, root, "S-20260822_120000", 1724311200L, null);
        Files.delete(world.resolve("DIM-1/region/r.0.0.mca"));
        SnapshotManifest target = backup(world, root, "S-20260822_130000", 1724313600L, base);
        Path out = exportAndRestore(root, base.getEpochSeconds(), target.getEpochSeconds());
        assertFalse(Files.exists(out.resolve("DIM-1/region/r.0.0.mca")));
        assertArrayEquals(nbt("overworld"), chunkNbt(out, "region/r.0.0.mca", 0));
        Path rebuiltRoot = tempDir.resolve("rebuilt");
        assertEquals(2, new ArchiveImporter(tempDir.resolve("archive"), rebuiltRoot).importAll());
        SnapshotManifest rebuilt = new SnapshotStore(rebuiltRoot).loadSnapshot(target.getEpochSeconds());
        assertEquals(List.of("region/r.0.0.mca"), rebuilt.getRegions().stream().map(RegionEntry::getFilename).toList());

        Files.delete(world.resolve("region/r.0.0.mca"));
        SnapshotManifest empty = backup(world, root, "S-20260822_140000", 1724317200L, target);
        Path archive = tempDir.resolve("archive");
        new ArchiveCreator(new SnapshotStore(root), new BlobStore(root, 6), 19)
                .createDiff(base.getEpochSeconds(), empty.getEpochSeconds(), archive);
        Path emptyOut = tempDir.resolve("restored-empty");
        new ArchiveRestorer(6).restore(archive, base.getEpochSeconds(), empty.getEpochSeconds(), emptyOut);
        assertFalse(Files.exists(emptyOut.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(emptyOut.resolve("DIM-1/region/r.0.0.mca")));
    }

    @Test
    void baselineMismatch_refusesRestore() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");

        SnapshotManifest sn1 = backup(world, backupRoot, "S-20260822_120000", 1724311200L, null);
        mutateWorld(world);
        SnapshotManifest sn2 = backup(world, backupRoot, "S-20260822_130000", 1724313600L,
                sn1);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, 19);
        exporter.createFull(sn1.getEpochSeconds(), archiveRoot);
        Path diffTar = exporter.createDiff(sn1.getEpochSeconds(), sn2.getEpochSeconds(), archiveRoot);


        // Rewrite diff tar with a wrong baseEpoch in snapshot.bin
        Path badDiff = tempDir.resolve("bad-diff.tar");
        rewriteDiffWithWrongBase(diffTar, badDiff);
        Files.move(badDiff, diffTar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Path out = tempDir.resolve("restore-mismatch");
        IOException ex = assertThrows(IOException.class,
                () -> new ArchiveRestorer(6).restore(archiveRoot, sn1.getEpochSeconds(), sn2.getEpochSeconds(), out));
        assertTrue(ex.getMessage().contains("Baseline mismatch"), ex.getMessage());
        assertFalse(Files.exists(out.resolve("region")), "nothing written on mismatch");
    }

    private void rewriteDiffWithWrongBase(Path src, Path dst) throws IOException {
        try (var in = Files.newInputStream(src);
                io.github.truscdo.foldbackup.core.archive.TarReader reader = new io.github.truscdo.foldbackup.core.archive.TarReader(in);
                var out = Files.newOutputStream(dst);
                io.github.truscdo.foldbackup.core.archive.TarWriter writer = new io.github.truscdo.foldbackup.core.archive.TarWriter(out)) {
            io.github.truscdo.foldbackup.core.archive.TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) {
                byte[] data = reader.readEntryData();
                if (e.name.equals("snapshot.bin")) {
                    io.github.truscdo.foldbackup.core.archive.ArchiveSnapshot meta = io.github.truscdo.foldbackup.core.archive.ArchiveSnapshotCodec.decode(data);
                    meta.baseEpoch = 9999999999L;
                    writer.putEntry(e.name, io.github.truscdo.foldbackup.core.archive.ArchiveSnapshotCodec.encode(meta));
                } else {
                    writer.putEntry(e.name, data);
                }
            }
        }
    }
}
