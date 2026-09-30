package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamingBackupTest {
    @TempDir
    Path temp;

    private Region region(byte value) {
        Region r = new Region(0, 0, 0);
        r.setChunk(0, new ChunkData(new byte[] { value }, 0, 0));
        r.setTimestamp(0, 42);
        return r;
    }

    @Test
    void changesBetweenPassesCommitActualContentAndDoNotPoisonSourceReuse() throws Exception {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Path mca = world.resolve("region/r.0.0.mca");
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region((byte) 1), 6);
        Path root = temp.resolve("store");
        BlobStore blobs = new BlobStore(root, 6) {
            public WriteSession newWriteSession() throws IOException {
                io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region((byte) 2), 6);
                return super.newWriteSession();
            }
        };
        var scan = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        assertTrue(scan.getErrors().isEmpty());
        assertEquals(1, scan.getRegionCount());
        var entry = scan.getRegions().getFirst();
        assertTrue(entry.getSourceMetadata().isEmpty());
        var written = io.github.truscdo.foldbackup.core.format.LrhTestSupport.openRegionLinear(blobs.readLrh(entry.getBlobPath()), 0, 0,
                0);
        assertArrayEquals(new byte[] { 2 }, written.getChunk(0).getRawChunk());
        assertEquals(io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(written)).hash()),
                entry.getContentHash());
        SnapshotStore store = new SnapshotStore(root);
        var snapshot = new SnapshotManifest("S-20260912_100000", 1, "world");
        scan.populateManifest(snapshot);
        store.saveSnapshot(snapshot);
        assertTrue(store.loadSnapshot(snapshot.getEpochSeconds()).getRegions().getFirst().getSourceMetadata().isEmpty());
    }

    @Test
    void ordinaryFileChangeUsesActualHashAndLength() throws Exception {
        Path world = temp.resolve("world");
        Files.createDirectories(world);
        Path file = world.resolve("level.dat");
        Files.writeString(file, "before");
        BlobStore blobs = new BlobStore(temp.resolve("store"), 6) {
            public WriteSession newWriteSession() throws IOException {
                Files.writeString(file, "after-and-longer");
                return super.newWriteSession();
            }
        };
        var scan = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        assertTrue(scan.getErrors().isEmpty());
        var entry = scan.getFiles().getFirst();
        assertEquals(McaHasher.hashFile(file), entry.getSourceHash());
        assertEquals(Files.size(file), entry.getSizeUncompressed());
        assertEquals("after-and-longer",
                new String(blobs.readFile(entry.getBlobPath()), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void contentHitKeepsReadLengthWhenSourceChangesAfterHashing() throws Exception {
        Path world = Files.createDirectory(temp.resolve("world"));
        Path file = world.resolve("level.dat");
        Files.writeString(file, "before");
        Path root = temp.resolve("store");
        BlobStore blobs = new BlobStore(root, 6);
        var original = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        var scan = new RegionProcessor(blobs, 6, new MetadataIndex()) {
            @Override
            McaHasher.FileHash inspectExtraFile(Path path) throws IOException {
                var inspected = super.inspectExtraFile(path);
                Files.writeString(path, "after-and-longer");
                return inspected;
            }
        }.scanWorld(world, null);
        assertTrue(scan.getErrors().isEmpty(), scan.getErrors().toString());
        var entry = scan.getFiles().getFirst();
        assertEquals(original.getFiles().getFirst().getBlobPath(), entry.getBlobPath());
        assertEquals(6, entry.getSizeUncompressed());
        assertTrue(entry.getSourceMetadata().isEmpty());
        assertEquals(0, scan.getNewBytes());

        var store = new SnapshotStore(root);
        var snapshot = new SnapshotManifest(SnapshotStore.generateSnapshotId(100), 100, "world");
        scan.populateManifest(snapshot);
        store.saveSnapshot(snapshot);
        Path archive = temp.resolve("archive");
        new io.github.truscdo.foldbackup.core.archive.ArchiveCreator(store, blobs, 6).createFull(100, archive, false, 1);
        Path restored = temp.resolve("restored");
        new io.github.truscdo.foldbackup.core.archive.ArchiveRestorer(6).restore(archive, 100L, null, restored);
        assertEquals("before", Files.readString(restored.resolve("level.dat")));
        assertEquals(1, new io.github.truscdo.foldbackup.core.archive.ArchiveImporter(archive, temp.resolve("imported")).importAll());
    }

    @Test
    void metadataAndContentHitsNeverOpenOutputAndWriteZeroNewBytes() throws Exception {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Path mca = world.resolve("region/r.0.0.mca");
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region((byte) 1), 6);
        AtomicInteger sessions = new AtomicInteger();
        Path root = temp.resolve("store");
        BlobStore blobs = new BlobStore(root, 6) {
            public WriteSession newWriteSession() throws IOException {
                sessions.incrementAndGet();
                return super.newWriteSession();
            }
        };
        SnapshotStore store = new SnapshotStore(root);
        RegionProcessor processor = new RegionProcessor(store::getIndex, blobs, 6);
        var first = processor.scanWorld(world, null);
        assertEquals(1, sessions.get());
        var snapshot = new SnapshotManifest("S-20260912_100000", 1, "world");
        first.populateManifest(snapshot);
        store.saveSnapshot(snapshot);
        var metadataHit = processor.scanWorld(world, snapshot);
        assertEquals(1, sessions.get());
        assertEquals(0, metadataHit.getNewBytes());
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region((byte) 1), 0);
        var contentHit = processor.scanWorld(world, snapshot);
        assertEquals(1, sessions.get());
        assertEquals(0, contentHit.getNewBytes());
        assertEquals(first.getRegions().getFirst().getBlobPath(), contentHit.getRegions().getFirst().getBlobPath());
    }
}
