package com.foldbackup.core.archive;

import com.foldbackup.core.HashUtil;
import com.foldbackup.core.format.*;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.store.SourceMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LrhdArchiveRecoveryTest {
    @TempDir Path temp;
    private static final String BASE = "S-20260914_120000", TARGET = "S-20260914_130000";

    private Path archives(boolean corrupt, boolean hidden) throws IOException {
        Path archive = Files.createDirectory(temp.resolve("archive"));
        var base = new ArchiveSnapshot(); base.type = "full"; base.sourceSnapshotId = BASE;
        base.epoch = 100; base.world = "world";
        Map<String, byte[]> baseObjects = new LinkedHashMap<>();
        // Physical archive and metadata ordering deliberately differ from signed numeric order.
        for (int rx : new int[]{10, -1, 5}) {
            var region = region(rx, rx == 5 ? new byte[]{9, 10} : new byte[]{1});
            byte[] object = com.foldbackup.core.format.LrhTestSupport.writeRegionLinearBytes(region, 3);
            String hash = HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(region)).hash());
            base.regions.add(new ArchiveSnapshot.RegionInfo("region/r." + rx + ".0.mca", 8192, hash,
                    SourceMetadata.empty()));
            baseObjects.putIfAbsent(hash, object);
        }
        base.regionCount = 3;
        try (var tar = new TarWriter(Files.newOutputStream(archive.resolve(ArchiveCreator.fullTarName(BASE))))) {
            tar.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(base));
            for (var object : baseObjects.entrySet())
                tar.putEntry(ArchiveCreator.regionObjectPath(object.getKey()), object.getValue());
        }
        var target = new ArchiveSnapshot(); target.type = "diff"; target.sourceSnapshotId = TARGET;
        target.epoch = 200; target.baseEpoch = 100; target.world = "world";
        Region changedTen = region(10, new byte[]{4, 5});
        Region changedMinusOne = region(-1, new byte[]{7, 8});
        Region unchangedFive = region(5, new byte[]{9, 10});
        if (!hidden) {
            target.regions.add(new ArchiveSnapshot.RegionInfo("region/r.10.0.mca", 8192,
                    HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(changedTen)).hash()), SourceMetadata.empty()));
            target.regions.add(new ArchiveSnapshot.RegionInfo("region/r.-1.0.mca", 8192,
                    HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(changedMinusOne)).hash()), SourceMetadata.empty()));
        }
        target.regions.add(new ArchiveSnapshot.RegionInfo("region/r.5.0.mca", 8192,
                HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(unchangedFive)).hash()), SourceMetadata.empty()));
        target.regionCount = target.regions.size();
        byte[] delta = com.foldbackup.core.format.LrhdTestSupport.write(3, List.of(
                new com.foldbackup.core.format.LrhdTestSupport.ChunkEntry(10, 0, 320, 0, 20, new byte[]{4, 5}),
                new com.foldbackup.core.format.LrhdTestSupport.ChunkEntry(-1, 0, -32, 0, 21, new byte[]{7, 8})));
        if (corrupt) delta[delta.length - 1] ^= 1; // Last frame checksum, after both region payloads.
        try (var tar = new TarWriter(Files.newOutputStream(archive.resolve(ArchiveCreator.diffTarName(BASE, TARGET))))) {
            tar.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(target));
            tar.putEntry("region/diff.lrhd", delta);
            if (hidden) {
                var files = new DiffComputer.FilesManifest(); files.baseEpoch = 100;
                files.files = List.of(new DiffComputer.FilesManifest.FileDiff("region/r.10.0.mca", null, 0,
                                SourceMetadata.empty(), true),
                        new DiffComputer.FilesManifest.FileDiff("region/r.-1.0.mca", null, 0,
                                SourceMetadata.empty(), true));
                tar.putEntry("files.bin", FilesManifestCodec.encode(files));
            }
        }
        return archive;
    }

    private static Region region(int rx, byte[] chunk) {
        var region = new Region(rx, 0, 0);
        region.setChunk(0, new ChunkData(chunk, rx * 32, 0));
        return region;
    }

    @Test void sequentialDeltaMergeIgnoresPhysicalTarAndMetadataOrdering() throws Exception {
        Path archive = archives(false, false), output = temp.resolve("world");
        new ArchiveRestorer(3, 4).restore(archive, 100L, 200L, output);
        for (int rx : new int[]{-1, 10}) {
            var region = AnvilTestSupport.readRegionAnvil(output.resolve("region/r." + rx + ".0.mca"));
            assertArrayEquals(rx == -1 ? new byte[]{7, 8} : new byte[]{4, 5}, region.getChunk(0).getRawChunk());
        }
        try (var plan = ArchiveRestorePlan.open(archive.resolve(ArchiveCreator.fullTarName(BASE)),
                archive.resolve(ArchiveCreator.diffTarName(BASE, TARGET)))) {
            var values = plan.runRegions(3, 4, null, null, (i, source) -> {
                RegionStream.inspect(source); return plan.regions.get(i).rx();
            });
            assertEquals(List.of(10, -1, 5), values, "Published metadata order remains stable");
        }
    }

    @Test void diffImportReusesUnchangedContentIdentityBlobs() throws Exception {
        Path archive = archives(false, false), store = temp.resolve("store");
        var events = new ArrayList<com.foldbackup.core.ProgressEvent>();
        assertEquals(2, new ArchiveImporter(archive, store, 4).importAll(events::add));

        var snapshots = new SnapshotStore(store);
        var base = snapshots.loadSnapshot(100);
        var target = snapshots.loadSnapshot(200);
        assertNotNull(base);
        assertNotNull(target);
        var unchangedBase = base.getRegions().stream()
                .filter(region -> region.getFilename().equals("region/r.5.0.mca")).findFirst().orElseThrow();
        var unchangedTarget = target.getRegions().stream()
                .filter(region -> region.getFilename().equals("region/r.5.0.mca")).findFirst().orElseThrow();
        var changedTarget = target.getRegions().stream()
                .filter(region -> region.getFilename().equals("region/r.10.0.mca")).findFirst().orElseThrow();

        assertEquals(unchangedBase.getContentHash(), unchangedTarget.getContentHash());
        assertEquals(unchangedBase.getBlobPath(), unchangedTarget.getBlobPath());
        assertEquals(unchangedBase.getSizeCompressed(), unchangedTarget.getSizeCompressed());
        assertNotEquals(unchangedBase.getContentHash(), changedTarget.getContentHash());
        var blobs = new com.foldbackup.core.store.BlobStore(store, 3);
        assertEquals(blobs.getRelativePath(changedTarget.getContentHash(), "lrh"), changedTarget.getBlobPath());
        assertTrue(blobs.exists(changedTarget.getContentHash(), "lrh"));

        var processing = events.stream().filter(event -> event.phase().equals("Merging missing regions"))
                .findFirst().orElseThrow();
        assertEquals(2, processing.total());
    }

    @Test void diffImportVerifiesDeletedDeltaRegionsWithoutRegionWorkers() throws Exception {
        Path archive = archives(false, true), store = temp.resolve("hidden-store");
        var events = new ArrayList<com.foldbackup.core.ProgressEvent>();
        assertEquals(2, new ArchiveImporter(archive, store, 4).importAll(events::add));

        var snapshots = new SnapshotStore(store);
        var base = snapshots.loadSnapshot(100);
        var target = snapshots.loadSnapshot(200);
        assertNotNull(base);
        assertNotNull(target);
        assertEquals(1, target.getRegions().size());
        assertEquals("region/r.5.0.mca", target.getRegions().get(0).getFilename());
        assertEquals(base.getRegions().stream().filter(region -> region.getFilename().equals("region/r.5.0.mca"))
                .findFirst().orElseThrow().getBlobPath(), target.getRegions().get(0).getBlobPath());
        assertEquals(0, events.stream().filter(event -> event.phase().equals("Merging missing regions"))
                .findFirst().orElseThrow().total());
    }

    @Test void regionsFromOneLrhdCanMergeInParallel() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        Path archive = archives(false, false);
        try (var plan = ArchiveRestorePlan.open(archive.resolve(ArchiveCreator.fullTarName(BASE)),
                archive.resolve(ArchiveCreator.diffTarName(BASE, TARGET)))) {
            var started = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var work = executor.submit(() -> plan.runRegions(3, 4, null, null, (index, source) -> {
                    started.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IOException("Region workers did not overlap");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Region worker interrupted", e);
                    }
                    RegionStream.inspect(source);
                    return Boolean.TRUE;
                }));
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "both Regions from the same LRHD should start before either finishes");
                release.countDown();
                assertEquals(3, work.get(10, java.util.concurrent.TimeUnit.SECONDS).size());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test void lastFrameChecksumFailureDoesNotPublishAnyRestoredFile() throws Exception {
        Path archive = archives(true, false), output = Files.createDirectory(temp.resolve("world"));
        Files.writeString(output.resolve("existing"), "keep");
        assertThrows(IOException.class, () -> new ArchiveRestorer(3, 4).restore(archive, 100L, 200L, output));
        assertEquals("keep", Files.readString(output.resolve("existing")));
        try (var files = Files.list(output)) { assertEquals(1, files.count()); }
    }

    @Test void hiddenDeltaPayloadStillMustPassChecksum() throws Exception {
        Path archive = archives(true, true), output = temp.resolve("world");
        assertThrows(IOException.class, () -> new ArchiveRestorer(3, 4).restore(archive, 100L, 200L, output));
        assertFalse(Files.exists(output));
    }

    @Test void corruptDiffCannotPublishImportedSnapshotOrLeavePendingBlobs() throws Exception {
        Path archive = archives(true, false), store = temp.resolve("store");
        assertThrows(IOException.class, () -> new ArchiveImporter(archive, store, 4).importAll());
        var snapshots = new SnapshotStore(store);
        assertNotNull(snapshots.loadSnapshot(100));
        assertNull(snapshots.loadSnapshot(200));
        try (var files = Files.walk(store)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".wip"))); }
    }
}
