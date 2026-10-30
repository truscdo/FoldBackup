package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Import-path behaviour that the baseline refactor introduced. */
class ArchiveImporterTest {
    @TempDir Path temp;
    private static final String BASE = "S-20260101_100000", TARGET = "S-20260101_110000";
    private static final long BASE_EPOCH = 1_000L, TARGET_EPOCH = 2_000L;

    // ── fixtures ──

    /** full: region/r.0.0.mca with chunk 0 = {1}; diff: the same Region with chunk 0 = {2}. */
    private Path diffArchive() throws IOException {
        Path archive = Files.createDirectory(temp.resolve("archive"));
        Region baseRegion = region(new byte[]{1});
        String baseHash = hash(baseRegion);
        var base = new ArchiveSnapshot();
        base.type = "full";
        base.sourceSnapshotId = BASE;
        base.epoch = BASE_EPOCH;
        base.world = "world";
        base.regionCount = 1;
        base.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 8192, baseHash, SourceMetadata.empty()));
        writeArchive(archive.resolve(ArchiveCreator.fullTarName(BASE)), ArchiveSnapshotCodec.encode(base),
                Map.of(ArchiveCreator.regionObjectPath(baseHash),
                        LrhTestSupport.writeRegionLinearBytes(baseRegion, 3)));

        Region targetRegion = region(new byte[]{2});
        var target = new ArchiveSnapshot();
        target.type = "diff";
        target.sourceSnapshotId = TARGET;
        target.epoch = TARGET_EPOCH;
        target.baseEpoch = BASE_EPOCH;
        target.world = "world";
        target.regionCount = 1;
        target.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 8192, hash(targetRegion),
                SourceMetadata.empty()));
        writeArchive(archive.resolve(ArchiveCreator.diffTarName(BASE, TARGET)), ArchiveSnapshotCodec.encode(target),
                Map.of("region/diff.lrhd", LrhdTestSupport.write(3, List.of(
                        new LrhdTestSupport.ChunkEntry(0, 0, 0, 0, 20, new byte[]{2})))));
        return archive;
    }

    private static Region region(byte[] chunk) {
        var region = new Region(0, 0, 0);
        if (chunk != null) region.setChunk(0, new ChunkData(chunk, 0, 0));
        return region;
    }

    private static String hash(Region region) throws IOException {
        return HashUtil.bytesToHex(RegionStream.inspect(RegionStream.of(region)).hash());
    }

    private static void writeArchive(Path tar, byte[] snapshot, Map<String, byte[]> entries) throws IOException {
        try (var writer = new TarWriter(Files.newOutputStream(tar))) {
            writer.putEntry("snapshot.bin", snapshot);
            for (var entry : entries.entrySet()) writer.putEntry(entry.getKey(), entry.getValue());
        }
    }

    private static void rewriteSnapshot(Path tar, Consumer<ArchiveSnapshot> edit) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var reader = new TarReader(Files.newInputStream(tar))) {
            TarReader.Entry entry;
            while ((entry = reader.nextEntry()) != null) entries.put(entry.name, reader.readEntryData());
        }
        ArchiveSnapshot meta = ArchiveSnapshotCodec.decode(entries.get("snapshot.bin"));
        edit.accept(meta);
        entries.put("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
        try (var writer = new TarWriter(Files.newOutputStream(tar))) {
            for (var entry : entries.entrySet()) writer.putEntry(entry.getKey(), entry.getValue());
        }
    }

    private static long blobs(Path store, String extension) throws IOException {
        try (var files = Files.walk(store.resolve("blobs"))) {
            return files.filter(path -> path.toString().endsWith(extension)).count();
        }
    }

    // ── tests ──

    @Test void diffImportsFromOnlineBaselineAfterFullArchiveIsGone() throws Exception {
        Path archive = diffArchive(), store = temp.resolve("store");
        assertEquals(2, new ArchiveImporter(archive, store, 4).importAll());

        // The diff snapshot (and the blob only it referenced) is dropped; the full tar is
        // removed from the archive library. Only the online baseline remains.
        new SnapshotStore(store).deleteSnapshot(TARGET_EPOCH);
        Files.delete(archive.resolve(ArchiveCreator.fullTarName(BASE)));

        assertEquals(1, new ArchiveImporter(archive, store, 4).importAll(), "diff imports from the snapshot baseline");
        SnapshotManifest manifest = new SnapshotStore(store).loadSnapshot(TARGET_EPOCH);
        assertNotNull(manifest);
        assertEquals(1, manifest.getRegions().size());
        var blobs = new BlobStore(store, 6);
        RegionEntry region = manifest.getRegions().get(0);
        assertTrue(blobs.existsPath(region.getBlobPath()), "merged Region was rebuilt from baseline + LRHD");
    }

    @Test void identicalArchivesAreSkippedAndTagsNeverDecideConflicts() throws Exception {
        Path archive = diffArchive(), store = temp.resolve("store");
        assertEquals(2, new ArchiveImporter(archive, store, 4).importAll());
        assertEquals(0, new ArchiveImporter(archive, store, 4).importAll(), "identical snapshots are skipped");

        // The archive now declares tags while the online snapshot has none: still identical.
        rewriteSnapshot(archive.resolve(ArchiveCreator.fullTarName(BASE)),
                meta -> meta.tags = List.of("keep-me"));
        assertEquals(0, new ArchiveImporter(archive, store, 4).importAll(), "tags do not make a conflict");
        SnapshotManifest existing = new SnapshotStore(store).loadSnapshot(BASE_EPOCH);
        assertTrue(existing.getTags() == null || existing.getTags().isEmpty(), "skipping does not rewrite tags");
    }

    @Test void duplicateFullArchivesWithIdenticalMetadataAreDeduplicated() throws Exception {
        Path archive = diffArchive();
        Files.copy(archive.resolve(ArchiveCreator.fullTarName(BASE)), archive.resolve("copy-full.tar"));
        assertEquals(2, new ArchiveImporter(archive, temp.resolve("store"), 4).importAll(),
                "one full + one diff; the duplicate full is ignored");
    }

    @Test void duplicateFullArchivesWithDifferentMetadataAreRejected() throws Exception {
        Path archive = diffArchive();
        Path copy = archive.resolve("copy-full.tar");
        Files.copy(archive.resolve(ArchiveCreator.fullTarName(BASE)), copy);
        rewriteSnapshot(copy, meta -> meta.world = "other-world");
        var failure = assertThrows(IOException.class,
                () -> new ArchiveImporter(archive, temp.resolve("store"), 4).importAll());
        assertTrue(failure.getMessage().contains("differs from"), failure.getMessage());
    }

    @Test void diffWithoutFullArchiveAndWithoutBaselineIsRejected() throws Exception {
        Path archive = diffArchive(), store = temp.resolve("store");
        Files.delete(archive.resolve(ArchiveCreator.fullTarName(BASE)));
        var failure = assertThrows(IOException.class, () -> new ArchiveImporter(archive, store, 4).importAll());
        assertTrue(failure.getMessage().contains("baseline snapshot"), failure.getMessage());
        assertNull(new SnapshotStore(store).loadSnapshot(TARGET_EPOCH));
    }

    @Test void regionsSharingOneContentIdentityStoreOneBlob() throws Exception {
        Path archive = Files.createDirectory(temp.resolve("archive"));
        Region empty = new Region(0, 0, 0);
        String emptyHash = hash(empty);
        var meta = new ArchiveSnapshot();
        meta.type = "full";
        meta.sourceSnapshotId = BASE;
        meta.epoch = BASE_EPOCH;
        meta.world = "world";
        meta.regionCount = 2;
        meta.regions.add(new ArchiveSnapshot.RegionInfo("region/r.0.0.mca", 0, emptyHash, SourceMetadata.empty()));
        meta.regions.add(new ArchiveSnapshot.RegionInfo("region/r.1.0.mca", 0, emptyHash, SourceMetadata.empty()));
        writeArchive(archive.resolve(ArchiveCreator.fullTarName(BASE)), ArchiveSnapshotCodec.encode(meta),
                Map.of(ArchiveCreator.regionObjectPath(emptyHash),
                        LrhTestSupport.writeRegionLinearBytes(empty, 3)));

        Path store = temp.resolve("store");
        assertEquals(1, new ArchiveImporter(archive, store, 4).importAll());
        SnapshotManifest manifest = new SnapshotStore(store).loadSnapshot(BASE_EPOCH);
        assertEquals(2, manifest.getRegions().size(), "both paths are recorded");
        assertEquals(manifest.getRegions().get(0).getBlobPath(), manifest.getRegions().get(1).getBlobPath(),
                "both paths share the content-addressed blob");
        assertEquals(1, blobs(store, ".lrh"), "parallel imports of one identity store a single blob");
    }

    @Test void conflictingSnapshotIsReportedInsteadOfOverwritten() throws Exception {
        Path archive = diffArchive(), store = temp.resolve("store");
        assertEquals(2, new ArchiveImporter(archive, store, 4).importAll());
        rewriteSnapshot(archive.resolve(ArchiveCreator.fullTarName(BASE)), meta -> meta.world = "other-world");
        var failure = assertThrows(IOException.class, () -> new ArchiveImporter(archive, store, 4).importAll());
        assertTrue(failure.getMessage().contains("already exists with different content"), failure.getMessage());
        assertEquals("world", new SnapshotStore(store).loadSnapshot(BASE_EPOCH).getWorldName(),
                "the existing snapshot is untouched");
    }
}
