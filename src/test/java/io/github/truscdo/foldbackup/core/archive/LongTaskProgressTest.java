package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.LrhTestSupport;
import io.github.truscdo.foldbackup.core.format.LrhdTestSupport;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LongTaskProgressTest {

    @TempDir
    Path temp;

    private static Region region(int rx, byte[] chunk) {
        var region = new Region(rx, 0, 0);
        region.setChunk(0, new ChunkData(chunk, rx * 32, 0));
        return region;
    }

    private static String hash(Region region) throws IOException {
        return HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(region)).hash());
    }

    @Test
    void diffArchiveCreationReportsProgressPerRegionRatherThanPerDirectory() throws Exception {
        Path storeDir = temp.resolve("store");
        var blobs = new BlobStore(storeDir, 1);
        var store = new SnapshotStore(storeDir);

        var base = new SnapshotManifest("S-20261116_100000", 1000, "world");
        for (int rx = 0; rx < 4; rx++) {
            base.addRegion(saveRegionBlob(blobs, "region/r." + rx + ".0.mca", "base-" + rx));
        }
        store.saveSnapshot(base);

        var target = new SnapshotManifest("S-20261116_110000", 2000, "world");
        for (int rx = 0; rx < 4; rx++) {
            target.addRegion(saveRegionBlob(blobs, "region/r." + rx + ".0.mca", "target-" + rx));
        }
        store.saveSnapshot(target);

        var events = new ArrayList<ProgressEvent>();
        new ArchiveCreator(store, blobs, 1).createDiffWithProgress(
                base.getEpochSeconds(), target.getEpochSeconds(), temp.resolve("archives"), 2, events::add);

        var writing = events.stream().filter(e -> e.phase().equals("Writing archive")).toList();
        assertFalse(writing.isEmpty());
        // 1 (snapshot.bin) + 4 (4 个 region 均变化，虽在同一目录 region/ 下仍需逐个上报) = 5
        assertEquals(5, writing.getLast().total());
        assertEquals(5, writing.getLast().completed());
        assertTrue(writing.stream().anyMatch(e -> e.item().equals("region/r.0.0.mca")));
        assertTrue(writing.stream().anyMatch(e -> e.item().equals("region/r.3.0.mca")));
    }

    @Test
    void diffRestorePipelineReportsCompletedRegionsBeforeWholeLrhdFrameFinishes() throws Exception {
        Path archive = Files.createDirectory(temp.resolve("pipeline-archive"));
        Region r0 = region(0, new byte[]{1});
        Region r1 = region(1, new byte[]{2});
        var base = new ArchiveSnapshot();
        base.type = "full";
        base.sourceSnapshotId = "S-20261116_100000";
        base.epoch = 100;
        base.world = "world";
        base.regionCount = 2;
        String h0 = hash(r0);
        String h1 = hash(r1);
        base.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 8192, h0, SourceMetadata.empty()));
        base.regions.add(new ArchiveSnapshot.RegionInfo("region/r.1.0.mca", 8192, h1, SourceMetadata.empty()));
        try (var writer = new TarWriter(Files.newOutputStream(archive.resolve("full.tar")))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(base));
            writer.putEntry(ArchiveCreator.regionObjectPath(h0), LrhTestSupport.writeRegionLinearBytes(r0, 1));
            writer.putEntry(ArchiveCreator.regionObjectPath(h1), LrhTestSupport.writeRegionLinearBytes(r1, 1));
        }

        var diff = new ArchiveSnapshot();
        diff.type = "diff";
        diff.sourceSnapshotId = "S-20261116_110000";
        diff.epoch = 200;
        diff.baseEpoch = 100;
        diff.world = "world";
        diff.regionCount = 2;
        Region r0New = region(0, new byte[]{3});
        Region r1New = region(1, new byte[]{4});
        diff.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 8192, hash(r0New), SourceMetadata.empty()));
        diff.regions.add(new ArchiveSnapshot.RegionInfo("region/r.1.0.mca", 8192, hash(r1New), SourceMetadata.empty()));
        try (var writer = new TarWriter(Files.newOutputStream(archive.resolve("diff.tar")))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(diff));
            writer.putEntry("region/diff.lrhd", LrhdTestSupport.write(1, List.of(
                    new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 10, new byte[]{3}),
                    new LrhdTestSupport.ChunkEntry(1, 0, 32, 0, 11, new byte[]{4}))));
        }

        var firstNotified = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var baseline = new ArchiveBaseline(archive.resolve("full.tar"));
                var diffIndex = OptionalCloseable.of(TarIndex.read(archive.resolve("diff.tar")));
                var plan = ArchiveRestorePlan.open(baseline, diffIndex.value.snapshot("diff"), diffIndex.value);
                var pool = Executors.newSingleThreadExecutor()) {
            var task = pool.submit(() -> plan.runRegions(ArchiveTasks.TaskWork.decompress(), 2, null,
                    index -> {
                        if (index == 0) firstNotified.countDown();
                    }, (index, source) -> {
                        RegionStream.inspect(source);
                        if (index == 1) {
                            try {
                                assertTrue(releaseSecond.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException(e);
                            }
                        }
                        return index;
                    }));
            try {
                // index=1 仍被栅栏阻塞、整帧尚未结束时，index=0 必须已经立即发出完成进度！
                assertTrue(firstNotified.await(5, TimeUnit.SECONDS),
                        "completed region must report progress immediately without waiting for whole LRHD frame");
            } finally {
                releaseSecond.countDown();
            }
            assertEquals(List.of(0, 1), task.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void verifyAllForwardsInnerProgressWithDeterministicTotals() throws Exception {
        Path storeDir = temp.resolve("verify-store");
        var blobs = new BlobStore(storeDir, 1);
        var store = new SnapshotStore(storeDir);
        var snap = new SnapshotManifest("S-20261116_100000", 1000, "world");
        snap.addRegion(saveRegionBlob(blobs, "region/r.0.0.mca", "r0"));
        snap.addRegion(saveRegionBlob(blobs, "region/r.1.0.mca", "r1"));
        store.saveSnapshot(snap);

        Path archiveRoot = temp.resolve("verify-archives");
        new ArchiveCreator(store, blobs, 1).createFull(snap.getEpochSeconds(), archiveRoot, false, 1);

        var events = new ArrayList<ProgressEvent>();
        ArchiveVerifier.Result result = new ArchiveVerifier().verifyAll(archiveRoot, events::add);
        assertTrue(result.ok(), result.errors.toString());
        assertTrue(events.stream().anyMatch(e -> e.phase().startsWith("[1/1 ") && e.total() == 2 && e.completed() == 2),
                events.toString());
        assertEquals("Verifying archives", events.getLast().phase());
        assertEquals(1, events.getLast().completed());
        assertEquals(1, events.getLast().total());
    }

    private RegionEntry saveRegionBlob(BlobStore blobs, String path, String data) throws IOException {
        try (var session = blobs.newWriteSession()) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            var summary = io.github.truscdo.foldbackup.core.format.LrhFormat.write(session.channel(), consumer -> {
                consumer.begin(0, 100);
                consumer.data(bytes, 0, bytes.length);
                consumer.end();
            }, 1);
            String hash = HashUtil.bytesToHex(summary.hash());
            var commit = session.commit(hash, "lrh");
            return new RegionEntry(path, hash, commit.path(), commit.size(), summary.nbtBytes());
        }
    }

    private record OptionalCloseable<T>(T value) implements AutoCloseable {
        static <T> OptionalCloseable<T> of(T value) { return new OptionalCloseable<>(value); }
        @Override public void close() {}
    }
}
