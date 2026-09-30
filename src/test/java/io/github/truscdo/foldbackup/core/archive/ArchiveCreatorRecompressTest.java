package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.backup.ScanResult;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.format.AnvilFormat;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
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
 * 全量导出重压缩（level 19）与多线程并行测试：
 * round-trip 保真（含 timestamps）、contentHash 不变、verbatim vs 重压
 * 内容一致、多线程结果确定性。
 */
class ArchiveCreatorRecompressTest {

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

        // region r.0.0: chunks (0,0) and (1,0), distinct timestamps
        Region r00 = new Region(0, 0, 1000);
        r00.setChunk(0, new ChunkData(nbt("chunk-0-0-v1"), 0, 0));
        r00.setTimestamp(0, 1000);
        r00.setChunk(1, new ChunkData(nbt("chunk-1-0-v1"), 1, 0));
        r00.setTimestamp(1, 2000);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), r00, 6);

        // region r.0.1: chunk (0,32)
        Region r01 = new Region(0, 1, 1000);
        r01.setChunk(0, new ChunkData(nbt("chunk-0-32-v1"), 0, 32));
        r01.setTimestamp(0, 3000);
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.1.mca"), r01, 6);

        Files.writeString(world.resolve("level.dat"), "level-v1");
        return world;
    }

    private SnapshotManifest backup(Path world, Path backupRoot, String id, long time) throws IOException {
        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var processor = new RegionProcessor(snapshotManager::getIndex, blobStore, 6);
        ScanResult result = processor.scanWorld(world, null, null);

        SnapshotManifest manifest = new SnapshotManifest(id, time, "world");
        manifest.setRegions(result.getRegions());
        manifest.setExtraFiles(result.getFiles());
        snapshotManager.saveSnapshot(manifest);
        return manifest;
    }

    private byte[] readTarEntry(Path tar, String name) throws IOException {
        try (var in = Files.newInputStream(tar);
                TarReader reader = new TarReader(in)) {
            TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) {
                if (e.name.equals(name)) {
                    return reader.readEntryData();
                }
                reader.readEntryData();
            }
        }
        throw new IOException("Entry not found: " + name);
    }

    private static void assertRegionEquals(Region expected, Region actual) {
        assertEquals(expected.getRegionX(), actual.getRegionX());
        assertEquals(expected.getRegionZ(), actual.getRegionZ());
        assertEquals(expected.chunkCount(), actual.chunkCount());
        for (int i = 0; i < Region.HEADER_CELLS; i++) {
            assertEquals(expected.getTimestamp(i), actual.getTimestamp(i), "ts@" + i);
            ChunkData a = expected.getChunk(i);
            ChunkData b = actual.getChunk(i);
            if (a == null) {
                assertNull(b, "chunk@" + i + " should be empty");
            } else {
                assertNotNull(b, "chunk@" + i + " should be present");
                assertArrayEquals(a.getRawChunk(), b.getRawChunk(), "chunk data @" + i);
            }
        }
    }

    // ── tests ──

    @Test
    void recompressedLrh_roundTripsToOriginalRegion() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");
        SnapshotManifest sn = backup(world, backupRoot, "S-20260822_120000", 1724311200L);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, ArchiveDefaults.ARCHIVE_LEVEL);
        Path tar = exporter.createFull(sn.getEpochSeconds(), archiveRoot, true, 1);

        for (RegionEntry re : sn.getRegions()) {
            byte[] lrh = readTarEntry(tar, ArchiveCreator.regionObjectPath(re.getContentHash()));
            assertTrue(io.github.truscdo.foldbackup.core.format.LrhTestSupport.verifyLinear(lrh),
                    "recompressed .lrh must verify: " + re.getFilename());

            // Parse recompressed blob and compare against the original .mca region
            String fname = re.getFilename();
            int slash = fname.lastIndexOf('/');
            int[] coords = AnvilFormat.parseRegionCoords(slash >= 0 ? fname.substring(slash + 1) : fname);
            Region original = AnvilTestSupport.readRegionAnvil(world.resolve(fname));
            Region parsed = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(lrh, coords[0], coords[1], 0);
            assertRegionEquals(original, parsed);
        }
    }

    @Test
    void recompress_preservesContentHash() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");
        SnapshotManifest sn = backup(world, backupRoot, "S-20260822_120000", 1724311200L);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, ArchiveDefaults.ARCHIVE_LEVEL);
        Path tar = exporter.createFull(sn.getEpochSeconds(), archiveRoot, true, 1);

        for (RegionEntry re : sn.getRegions()) {
            byte[] original = blobStore.readLrh(re.getBlobPath());
            byte[] recompressed = readTarEntry(tar, ArchiveCreator.regionObjectPath(re.getContentHash()));
            assertArrayEquals(io.github.truscdo.foldbackup.core.format.LrhTestSupport.readContentHashFromBytes(original),
                    io.github.truscdo.foldbackup.core.format.LrhTestSupport.readContentHashFromBytes(recompressed),
                    "contentHash must be identical after recompression: " + re.getFilename());
        }
    }

    @Test
    void recompress_matchesVerbatimChunkData() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");
        SnapshotManifest sn = backup(world, backupRoot, "S-20260822_120000", 1724311200L);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, ArchiveDefaults.ARCHIVE_LEVEL);
        Path tarRe = exporter.createFull(sn.getEpochSeconds(), archiveRoot.resolve("re"), true, 1);
        Path tarVb = exporter.createFull(sn.getEpochSeconds(), archiveRoot.resolve("vb"), false, 0);

        for (RegionEntry re : sn.getRegions()) {
            byte[] recompressed = readTarEntry(tarRe, ArchiveCreator.regionObjectPath(re.getContentHash()));
            byte[] verbatim = readTarEntry(tarVb, ArchiveCreator.regionObjectPath(re.getContentHash()));
            Region a = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(recompressed, 0, 0, 0);
            Region b = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(verbatim, 0, 0, 0);
            assertRegionEquals(a, b);
        }
    }

    @Test
    void parallelThreads_produceIdenticalTar() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");
        SnapshotManifest sn = backup(world, backupRoot, "S-20260822_120000", 1724311200L);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, ArchiveDefaults.ARCHIVE_LEVEL);
        Path tar1 = exporter.createFull(sn.getEpochSeconds(), archiveRoot.resolve("t1"), true, 1);
        Path tar4 = exporter.createFull(sn.getEpochSeconds(), archiveRoot.resolve("t4"), true, 4);

        for (RegionEntry re : sn.getRegions()) {
            assertArrayEquals(readTarEntry(tar1, ArchiveCreator.regionObjectPath(re.getContentHash())),
                    readTarEntry(tar4, ArchiveCreator.regionObjectPath(re.getContentHash())),
                    "parallel recompression must be deterministic: " + re.getFilename());
        }
    }

    @Test
    void defaultExportFull_recompresses() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path backupRoot = tempDir.resolve("backups");
        Path archiveRoot = tempDir.resolve("archive");
        SnapshotManifest sn = backup(world, backupRoot, "S-20260822_120000", 1724311200L);

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);
        var exporter = new ArchiveCreator(snapshotManager, blobStore, ArchiveDefaults.ARCHIVE_LEVEL);
        Path tar = exporter.createFull(sn.getEpochSeconds(), archiveRoot);

        for (RegionEntry re : sn.getRegions()) {
            byte[] lrh = readTarEntry(tar, ArchiveCreator.regionObjectPath(re.getContentHash()));
            assertTrue(io.github.truscdo.foldbackup.core.format.LrhTestSupport.verifyLinear(lrh),
                    "default export must produce valid .lrh");
            assertArrayEquals(
                    io.github.truscdo.foldbackup.core.format.LrhTestSupport
                            .readContentHashFromBytes(blobStore.readLrh(re.getBlobPath())),
                    io.github.truscdo.foldbackup.core.format.LrhTestSupport.readContentHashFromBytes(lrh),
                    "default export must recompress (contentHash preserved)");
        }
    }

    @Test
    void fullProgressCoversVerbatimSerialAndParallelExports() throws Exception {
        Path world = createWorld(tempDir, "progress-world");
        Path root = tempDir.resolve("progress-backups");
        SnapshotManifest sn = backup(world, root, "S-20260822_120000", 1724311200L);
        var exporter = new ArchiveCreator(new SnapshotStore(root), new BlobStore(root, 6), 19);
        Thread caller = Thread.currentThread();
        for (int mode = 0; mode < 3; mode++) {
            var events = new java.util.ArrayList<ProgressEvent>();
            Path output = tempDir.resolve("progress-" + mode);
            exporter.createFullWithProgress(sn.getEpochSeconds(), output, mode != 0, mode == 2 ? 4 : 1, event -> {
                assertSame(caller, Thread.currentThread());
                if (event.phase().equals("Finalizing archive") && event.completed() == 1) {
                    assertTrue(Files.exists(output.resolve(ArchiveCreator.fullTarName(sn.getSnapshotId()))));
                }
                events.add(event);
            });
            String regionPhase = mode == 0 ? "Copying regions" : "Recompressing regions";
            assertTrue(events.stream().anyMatch(e -> e.phase().equals(regionPhase)
                    && e.completed() == sn.getRegions().size() && e.total() == sn.getRegions().size()));
            assertTrue(events.stream().anyMatch(e -> e.phase().equals("Copying files")
                    && e.completed() == sn.getExtraFiles().size()));
            assertEquals("Finalizing archive", events.getLast().phase());
            assertEquals(1, events.getLast().completed());
            for (ProgressEvent e : events) {
                assertTrue(e.completed() >= 0 && e.completed() <= e.total(), e.toString());
            }
        }
    }

    @Test
    void parallelExportWithTightBudgetAndOversizedRegionMatchesPaths() throws Exception {
        Path root = tempDir.resolve("backups");
        Files.createDirectories(root);
        var regions = new java.util.ArrayList<RegionEntry>();
        var expected = new java.util.HashMap<String, byte[]>();
        for (int i = 0; i < 8; i++) {
            // Manifest estimates must not require buffering a whole result.
            long size = i % 3 == 1 ? 512L << 20 : 32L << 20;
            Region content = new Region(i, 0, 0);
            content.setChunk(0, new ChunkData(nbt("distinct-region-" + i), i * 32, 0));
            content.setTimestamp(0, 100 + i);
            byte[] lrh = io.github.truscdo.foldbackup.core.format.LrhTestSupport.writeRegionLinearBytes(content, 6);
            String contentHash = io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                    io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.RegionStream.of(content))
                            .hash());
            String blob = "test-" + i + ".lrh";
            Files.write(root.resolve(blob), lrh);
            String path = "region/r." + i + ".0.mca";
            regions.add(new RegionEntry(path, contentHash, blob, lrh.length, size));
            expected.put(ArchiveCreator.regionObjectPath(contentHash),
                    io.github.truscdo.foldbackup.core.format.LrhTestSupport.recompress(lrh, 19));
        }
        var blobs = new BlobStore(root, 6);
        var exporter = new ArchiveCreator(new SnapshotStore(root), blobs, 19);
        for (long budget : new long[] { 1L << 20, 160L << 20 }) {
            Path archive = tempDir.resolve("bounded-" + budget + ".tar");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> {
                try (var channel = java.nio.channels.FileChannel.open(archive,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                        var tar = new TarWriter(channel)) {
                    exporter.exportRegionsParallel(tar, regions, 4, budget);
                }
            });
            try (var reader = new TarReader(Files.newInputStream(archive))) {
                var seen = new java.util.HashSet<String>();
                for (RegionEntry re : regions) {
                    String name = reader.nextEntry().name;
                    assertTrue(seen.add(name));
                    assertEquals(ArchiveCreator.regionObjectPath(re.getContentHash()), name);
                    assertArrayEquals(expected.get(name), reader.readEntryData());
                }
                assertNull(reader.nextEntry());
            }
        }
    }

    @Test
    void realCompressedOutputExceedsPoolAndStillRoundTrips() throws Exception {
        Region region = new Region(0, 0, 0);
        byte[] nbt = new byte[512 * 1024];
        new java.util.Random(27).nextBytes(nbt);
        region.setChunk(0, new ChunkData(nbt, 0, 0));
        region.setTimestamp(0, 123);
        Path source = tempDir.resolve("source.lrh");
        io.github.truscdo.foldbackup.core.format.LrhTestSupport.writeRegionLinear(source, region, 3);
        String contentHash = io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.RegionStream.of(region)).hash());
        var entry = new RegionEntry("region/r.0.0.mca", contentHash, "source.lrh", Files.size(source), nbt.length);
        var exporter = new ArchiveCreator(new SnapshotStore(tempDir), new BlobStore(tempDir, 3), 19);
        Path archive = tempDir.resolve("large.tar");
        try (var channel = java.nio.channels.FileChannel.open(archive,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                var tar = new TarWriter(channel)) {
            var metrics = exporter.exportRegionsParallel(tar, List.of(entry), 2, 1L << 20);
            assertTrue(metrics.peakBufferBytes() <= 128 * 1024);
        }
        byte[] lrh = readTarEntry(archive, ArchiveCreator.regionObjectPath(contentHash));
        assertTrue(lrh.length > 128 * 1024);
        Region restored = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(lrh, 0, 0, 0);
        assertArrayEquals(nbt, restored.getChunk(0).getRawChunk());
        assertEquals(123, restored.getTimestamp(0));
    }

    @Test
    void corruptSourceRemovesTemporaryArchiveAndPreservesExistingArchive() throws Exception {
        Path world = createWorld(tempDir, "corrupt-world");
        Path root = tempDir.resolve("corrupt-backups");
        SnapshotManifest snapshot = backup(world, root, "S-20260822_120000", 1724311200L);
        Path blob = root.resolve(snapshot.getRegions().getFirst().getBlobPath());
        byte[] bytes = Files.readAllBytes(blob);
        bytes[LrhFormat.COMPRESSED_DATA_OFFSET] ^= 1;
        Files.write(blob, bytes); // Keep metadata readable so failure occurs after tar.tmp is created.
        Path output = tempDir.resolve("corrupt-output");
        Files.createDirectories(output);
        Path archive = output.resolve(ArchiveCreator.fullTarName(snapshot.getSnapshotId()));
        byte[] previous = { 1, 2, 3 };
        Files.write(archive, previous);
        var exporter = new ArchiveCreator(new SnapshotStore(root), new BlobStore(root, 6), 19);
        assertThrows(IOException.class, () -> exporter.createFull(snapshot.getEpochSeconds(), output, true, 2));
        assertArrayEquals(previous, Files.readAllBytes(archive));
        assertFalse(Files.exists(archive.resolveSibling(archive.getFileName() + ".tmp")));
    }

    @Test
    void parallelReadFailurePropagatesWithoutWaitingForMemory() throws Exception {
        Path world = createWorld(tempDir, "world");
        Path root = tempDir.resolve("backups");
        SnapshotManifest sn = backup(world, root, "S-20260822_120000", 1724311200L);
        var regions = new java.util.ArrayList<>(sn.getRegions());
        regions.addFirst(new RegionEntry("region/r.9.9.mca", "0".repeat(32), "blobs/missing.lrh", 1, 1));
        var exporter = new ArchiveCreator(new SnapshotStore(root), new BlobStore(root, 6), 19);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> {
            try (var channel = java.nio.channels.FileChannel.open(tempDir.resolve("failure.tar"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                    var tar = new TarWriter(channel)) {
                assertThrows(IOException.class,
                        () -> exporter.exportRegionsParallel(tar, regions, 4, 1L << 20));
            }
        });
    }
}
