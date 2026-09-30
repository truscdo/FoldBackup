package io.github.truscdo.foldbackup.core.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import static org.junit.jupiter.api.Assertions.*;

class TarIndexTest {
    @TempDir Path temp;
    @Test void independentBoundedStreamsPreserveLongNamesAndEmptyEntries() throws Exception {
        Path archive = temp.resolve("test.tar");
        String longName = "directory/".repeat(15) + "payload.bin";
        byte[] data = new byte[10000];
        new Random(19).nextBytes(data);
        try (var writer = new TarWriter(Files.newOutputStream(archive))) {
            writer.putEntry(longName, data);
            writer.putEntry("empty", new byte[0]);
            writer.putEntry("last", new byte[] {1, 2, 3});
        }
        var index = TarIndex.read(archive);
        assertEquals(3, index.entries.size());
        try (var a = index.entries.get(longName).open(); var b = index.entries.get("last").open()) {
            assertEquals(data[0] & 255, a.read());
            assertArrayEquals(new byte[] {1, 2, 3}, b.readAllBytes());
            assertEquals(-1, b.read());
            assertArrayEquals(Arrays.copyOfRange(data, 1, data.length), a.readAllBytes());
        }
        assertArrayEquals(new byte[0], index.entries.get("empty").bytes());
        var results = ArchiveTasks.run(20, 4, i -> index.entries.get(longName).bytes());
        for (byte[] actual : results) assertArrayEquals(data, actual);
    }

    @Test void rejectsTruncationDuplicatesAndEscapingPaths() throws Exception {
        Path archive = temp.resolve("bad.tar");
        try (var writer = new TarWriter(Files.newOutputStream(archive))) {
            writer.putEntry("region", new byte[1000]);
        }
        try (var file = java.nio.channels.FileChannel.open(archive, StandardOpenOption.WRITE)) { file.truncate(700); }
        assertThrows(IOException.class, () -> TarIndex.read(archive));
        for (String name : List.of("duplicate", "../outside", "C:/outside", "a/../b")) {
            try (var writer = new TarWriter(Files.newOutputStream(archive))) {
                writer.putEntry(name, new byte[0]);
                writer.putEntry(name, new byte[0]);
            }
            assertThrows(IOException.class, () -> TarIndex.read(archive));
        }
    }

    @Test void corruptedRegionDoesNotCommitSnapshotAndCleansBlobSessions() throws Exception {
        Path archiveRoot = temp.resolve("archive");
        Files.createDirectories(archiveRoot);
        var meta = new ArchiveSnapshot();
        meta.type = "full"; meta.sourceSnapshotId = "S-20260914_120000"; meta.epoch = 100;
        meta.world = "test"; meta.regionCount = 2;
        var emptyRegion = new io.github.truscdo.foldbackup.core.format.Region(0, 0, 0);
        byte[] firstLrh = io.github.truscdo.foldbackup.core.format.LrhTestSupport.writeRegionLinearBytes(emptyRegion, 6);
        String firstHash = io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                io.github.truscdo.foldbackup.core.format.RegionStream.inspect(io.github.truscdo.foldbackup.core.format.RegionStream.of(emptyRegion)).hash());
        String secondHash = "1".repeat(32);
        meta.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 8192, firstHash, SourceMetadata.empty()));
        meta.regions.add(new ArchiveSnapshot.RegionInfo("region/r.1.0.mca", 8192, secondHash, SourceMetadata.empty()));
        Path archive = archiveRoot.resolve(ArchiveCreator.fullTarName(meta.sourceSnapshotId));
        try (var writer = new TarWriter(Files.newOutputStream(archive))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
            writer.putEntry(ArchiveCreator.regionObjectPath(firstHash), firstLrh);
            writer.putEntry(ArchiveCreator.regionObjectPath(secondHash), new byte[100]);
        }
        Path store = temp.resolve("store");
        assertThrows(IOException.class, () -> new ArchiveImporter(archiveRoot, store, 2).importAll());
        assertNull(new io.github.truscdo.foldbackup.core.store.SnapshotStore(store).loadSnapshot(meta.epoch));
        try (var files = Files.walk(store)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".wip"))); }
    }

    @Test void conflictingRegionAndFileAreRejectedBeforeWriting() throws Exception {
        Path archiveRoot = temp.resolve("conflict");
        Files.createDirectories(archiveRoot);
        var meta = new ArchiveSnapshot();
        meta.type = "full"; meta.sourceSnapshotId = "S-20260914_120000"; meta.epoch = 100;
        meta.regionCount = 1;
        meta.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 0, "0".repeat(32), SourceMetadata.empty()));
        meta.files.add(new ArchiveSnapshot.FileInfo("region/r.0.0.mca", 0, "1".repeat(32), SourceMetadata.empty()));
        Path archive = archiveRoot.resolve(ArchiveCreator.fullTarName(meta.sourceSnapshotId));
        try (var writer = new TarWriter(Files.newOutputStream(archive))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
            writer.putEntry(ArchiveCreator.regionObjectPath("0".repeat(32)), new byte[0]);
            writer.putEntry(ArchiveCreator.fileObjectPath("1".repeat(32)), new byte[0]);
        }
        Path world = temp.resolve("must-not-exist");
        assertThrows(IOException.class, () -> new ArchiveRestorer(6, 2).restore(archiveRoot, meta.epoch, null, world));
        assertFalse(Files.exists(world));
    }
}
