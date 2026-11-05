package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline rollback: a world is backed up, then modified, then restored in place.
 * <p>
 * The existing restore tests export into a blank directory. These exercise the user-visible
 * rollback case, where the target already holds newer content that must go back to the snapshot.
 */
class OfflineRollbackTest {

    @TempDir
    Path temp;

    private OfflineRollbackFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new OfflineRollbackFixture(temp);
    }

    // ── A. core rollback semantics ──

    @Test
    void changedChunkRollsBackToSnapshotContent() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.writeChunk(0, OfflineRollbackFixture.utf8("chunk-0-0-v2-mutated"),
                OfflineRollbackFixture.MTIME_V2);

        var outcome = fixture.restoreLatest(false);

        assertEquals(1, outcome.stats().changedRegions());
        assertEquals("chunk-0-0-v1", fixture.chunkText(0));
        assertEquals("chunk-1-0-v1", fixture.chunkText(1), "untouched neighbour stays");
    }

    @Test
    void deletedChunkReappearsAfterRollback() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.removeChunk(1, OfflineRollbackFixture.MTIME_V2);
        assertNull(fixture.chunkText(1));

        fixture.restoreLatest(false);

        assertEquals("chunk-1-0-v1", fixture.chunkText(1));
    }

    @Test
    void chunkAddedAfterSnapshotIsDiscardedWhenTheRegionIsRewritten() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.writeChunk(2, OfflineRollbackFixture.utf8("chunk-2-0-new"),
                OfflineRollbackFixture.MTIME_V2);
        assertEquals("chunk-2-0-new", fixture.chunkText(2));

        fixture.restoreLatest(false);

        assertNull(fixture.chunkText(2), "a region is replaced as one unit");
    }

    @Test
    void levelDatAndPlayerDataRollBack() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.addFile("level.dat", "level-v2-modified");
        fixture.addFile("playerdata/a.dat", "player-v2-modified");

        var outcome = fixture.restoreLatest(false);

        assertEquals(2, outcome.stats().changedFiles());
        assertEquals("level-v1", fixture.readFile("level.dat"));
        assertEquals("player-v1", fixture.readFile("playerdata/a.dat"));
    }

    @Test
    void extraFileIsKeptByDefaultAndRemovedWithDeleteExtra() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.addFile("data/extra.txt", "extra");

        fixture.restoreLatest(false);
        assertEquals("extra", fixture.readFile("data/extra.txt"));

        fixture.restoreLatest(true);
        assertFalse(Files.exists(fixture.world().resolve("data/extra.txt")));
        assertFalse(Files.exists(fixture.world().resolve("data")), "the emptied directory is removed");
    }

    // ── B. decision semantics ──

    /**
     * Pins the documented trade-off: when size and mtime both match, the checker trusts the
     * metadata and never re-hashes the content. Changing the bytes while restoring the timestamp
     * keeps the modified value, which is intentional for speed.
     */
    @Test
    void sameSizeAndMtimeIsTreatedAsUnchangedEvenWhenContentDiffers() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        Path levelDat = fixture.world().resolve("level.dat");
        var recorded = Files.getLastModifiedTime(levelDat);

        // "level-v1" and "level-X1" have the same byte length, so size matches too.
        Files.writeString(levelDat, "level-X1", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(levelDat, recorded);

        var outcome = fixture.restoreLatest(false);

        assertEquals(0, outcome.stats().changedFiles(), "size and mtime match, so the file is skipped");
        assertEquals("level-X1", fixture.readFile("level.dat"),
                "the unchanged metadata is trusted without re-hashing");
    }

    // ── C. external .mcc consistency ──

    @Test
    void oversizedChunkComesBackAsExternalMcc() throws IOException {
        fixture.writeWorld("v1");
        byte[] oversized = OfflineRollbackFixture.incompressible(OfflineRollbackFixture.OVERSIZED_CHUNK);
        fixture.writeChunk(0, oversized, OfflineRollbackFixture.MTIME_V1);
        assertTrue(fixture.mccFiles().contains("c.0.0.mcc"), fixture.mccFiles().toString());
        fixture.backup();

        fixture.writeChunk(0, OfflineRollbackFixture.utf8("small-again"), OfflineRollbackFixture.MTIME_V2);

        fixture.restoreLatest(false);

        assertArrayEquals(oversized, fixture.chunkBytes(0));
        assertTrue(fixture.mccFiles().contains("c.0.0.mcc"),
                "the oversized chunk is rebuilt in its external form: " + fixture.mccFiles());
    }

    @Test
    void staleMccIsRemovedByTheRegionUnitEvenWithoutDeleteExtra() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();

        fixture.writeChunk(0, OfflineRollbackFixture.incompressible(OfflineRollbackFixture.OVERSIZED_CHUNK),
                OfflineRollbackFixture.MTIME_V2);
        assertTrue(fixture.mccFiles().contains("c.0.0.mcc"), fixture.mccFiles().toString());

        fixture.restoreLatest(false);

        assertEquals("chunk-0-0-v1", fixture.chunkText(0));
        assertFalse(fixture.mccFiles().contains("c.0.0.mcc"),
                "the region unit is replaced whole, so its stale external chunk goes too");
    }

    // ── D. failure and protection ──

    @Test
    void refusesRollbackWhileAnotherHolderOwnsTheWorldLock() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        fixture.writeChunk(0, OfflineRollbackFixture.utf8("chunk-0-0-v2"),
                OfflineRollbackFixture.MTIME_V2);

        try (WorldSaveLock ignored = WorldSaveLock.acquireRoot(fixture.world())) {
            IOException failure = assertThrows(IOException.class, () -> fixture.restoreLatest(false));
            assertTrue(failure.getMessage().contains("in use"), failure.getMessage());
        }

        assertEquals("chunk-0-0-v2", fixture.chunkText(0), "a refused rollback changes nothing");
    }

    @Test
    void refusesOutputWhoseParentIsAFile() throws IOException {
        fixture.writeWorld("v1");
        fixture.backup();
        Path blocker = Files.writeString(temp.resolve("blocker"), "not a directory");

        IOException failure = assertThrows(IOException.class,
                () -> fixture.restoreTo(blocker.resolve("world"), false));

        assertTrue(failure.getMessage().contains("cannot be created"), failure.getMessage());
        assertTrue(Files.isRegularFile(blocker), "the conflicting file is left untouched");
    }

    @Test
    void partialFailureKeepsLandedEntriesAndCleansStagingDirectories() throws IOException {
        fixture.writeWorld("v1");
        SnapshotManifest manifest = fixture.backup();
        // A distinct mtime keeps the region off the size-and-mtime fast path, so it must be written.
        fixture.writeWorld("v2", OfflineRollbackFixture.MTIME_V2);

        try (var real = new SnapshotRestoreSource(manifest,
                new BlobStore(fixture.storeRoot(), OfflineRollbackFixture.COMPRESSION));
                var failing = new FailingRegionSource(real)) {
            IOException failure = assertThrows(IOException.class,
                    () -> fixture.restoreWith(failing, fixture.world(), false, false));
            assertTrue(failure.getMessage().contains("simulated region failure"), failure.getMessage());
        }

        assertEquals("level-v1", fixture.readFile("level.dat"), "files landed before the region phase stay");
        assertEquals("chunk-0-0-v2", fixture.chunkText(0), "the failed region keeps its old content");
        assertNoTemporaries();
    }

    // ── E. archive and zip sources ──

    @Test
    void fullArchiveRollsBackToSnapshot() throws IOException {
        fixture.writeWorld("v1");
        SnapshotManifest manifest = fixture.backup();
        Path full = fixture.createFullArchive(manifest.getEpochSeconds());
        assertTrue(Files.isRegularFile(full));

        fixture.writeChunk(0, OfflineRollbackFixture.utf8("chunk-0-0-v2"),
                OfflineRollbackFixture.MTIME_V2);
        fixture.addFile("level.dat", "level-v2-modified");

        fixture.restoreArchive(full, fixture.world(), false);

        assertEquals("chunk-0-0-v1", fixture.chunkText(0));
        assertEquals("level-v1", fixture.readFile("level.dat"));
    }

    @Test
    void diffArchiveRollsBackToItsOwnSnapshot() throws IOException {
        fixture.writeWorld("v1");
        SnapshotManifest base = fixture.backup();

        fixture.writeChunk(0, OfflineRollbackFixture.utf8("chunk-0-0-v2"),
                OfflineRollbackFixture.MTIME_V2);
        SnapshotManifest target = fixture.backup();

        Path full = fixture.createFullArchive(base.getEpochSeconds());
        Path diff = fixture.createDiffArchive(base.getEpochSeconds(), target.getEpochSeconds());
        assertNotEquals(full, diff);

        // Same length as v2 plus a new mtime: content is the only signal that differs.
        fixture.writeChunk(0, OfflineRollbackFixture.utf8("chunk-0-0-v3"),
                OfflineRollbackFixture.MTIME_V3);

        fixture.restoreArchive(diff, fixture.world(), false);

        assertEquals("chunk-0-0-v2", fixture.chunkText(0));
    }

    @Test
    void zipExportUnpacksIntoAUsableWorld() throws IOException {
        fixture.writeWorld("v1");
        SnapshotManifest manifest = fixture.backup();

        Path zip = fixture.exportZip(manifest.getSnapshotId(), temp.resolve("snapshot.zip"));
        Path unpacked = temp.resolve("unpacked");
        fixture.unzip(zip, unpacked);

        assertEquals("level-v1", Files.readString(unpacked.resolve("level.dat"), StandardCharsets.UTF_8));
        Region region = AnvilTestSupport.readRegionAnvil(unpacked.resolve("region/r.0.0.mca"));
        assertEquals("chunk-0-0-v1", new String(region.getChunk(0).getRawChunk(), StandardCharsets.UTF_8));
    }

    // ── helpers ──

    private void assertNoTemporaries() throws IOException {
        try (var walk = Files.walk(fixture.world())) {
            List<Path> leftovers = walk
                    .filter(path -> path.getFileName().toString().contains(".foldbackup"))
                    .toList();
            assertTrue(leftovers.isEmpty(), leftovers.toString());
        }
    }

    /** Lets the file phase land, then fails the region phase it runs into next. */
    private static final class FailingRegionSource implements RestoreSource {
        private final RestoreSource delegate;

        FailingRegionSource(RestoreSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<RestoreEntry> entries() {
            return delegate.entries();
        }

        @Override
        public InputStream openFile(RestoreEntry entry) throws IOException {
            return delegate.openFile(entry);
        }

        @Override
        public void readRegions(List<RestoreEntry> regions, RegionConsumer consumer, int threads)
                throws IOException {
            throw new IOException("simulated region failure");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
