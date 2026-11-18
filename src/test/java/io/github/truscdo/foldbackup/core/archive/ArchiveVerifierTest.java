package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveVerifierTest {

    @TempDir
    Path temp;

    private static RegionEntry saveRegion(BlobStore blobs, String path, String data) throws IOException {
        try (var session = blobs.newWriteSession()) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            var summary = LrhFormat.write(session.channel(), consumer -> {
                consumer.begin(0, 100);
                consumer.data(bytes, 0, bytes.length);
                consumer.end();
            }, 1);
            String hash = HashUtil.bytesToHex(summary.hash());
            var commit = session.commit(hash, "lrh");
            return new RegionEntry(path, hash, commit.path(), commit.size(), summary.nbtBytes());
        }
    }

    private static void rewriteSnapshotWorld(Path tar, String worldName) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var input = Files.newInputStream(tar); var reader = new TarReader(input)) {
            TarReader.Entry entry;
            while ((entry = reader.nextEntry()) != null) {
                entries.put(entry.name, reader.readEntryData());
            }
        }
        ArchiveSnapshot meta = ArchiveSnapshotCodec.decode(entries.get("snapshot.bin"));
        meta.world = worldName;
        entries.put("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
        try (var output = Files.newOutputStream(tar); var writer = new TarWriter(output)) {
            for (var entry : entries.entrySet()) {
                writer.putEntry(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void expectedFullNameAndBaselineErrorMessageMatchArchiveNaming() {
        long epoch = 1_000L;
        String expected = ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(epoch));
        assertEquals(expected, ArchiveFormatUtil.expectedFullName(epoch));

        String missing = ArchiveFormatUtil.baselineArchiveErrorMessage(
                epoch, new IOException("No full archive for epoch: " + epoch));
        assertEquals("Required baseline archive " + expected + " is missing", missing);

        IOException conflict = new IOException(ArchiveFormatUtil.conflictingFullMessage(
                epoch, java.util.List.of("a.tar", "b.tar")));
        String ambiguous = ArchiveFormatUtil.baselineArchiveErrorMessage(epoch, conflict);
        assertTrue(ambiguous.startsWith("Required baseline archive " + expected + " is ambiguous: "), ambiguous);
        assertTrue(ambiguous.contains("a.tar"), ambiguous);
        assertTrue(ambiguous.contains("b.tar"), ambiguous);
    }

    @Test
    void verifyArchiveDistinguishesMissingAndConflictingBaseline() throws IOException {
        Path storeDir = temp.resolve("store");
        var blobs = new BlobStore(storeDir, 1);
        var store = new SnapshotStore(storeDir);

        long baseEpoch = 1_000L;
        long diffEpoch = 2_000L;
        var base = new SnapshotManifest(SnapshotStore.generateSnapshotId(baseEpoch), baseEpoch, "world");
        base.addRegion(saveRegion(blobs, "region/r.0.0.mca", "chunk-v1"));
        store.saveSnapshot(base);

        var target = new SnapshotManifest(SnapshotStore.generateSnapshotId(diffEpoch), diffEpoch, "world");
        target.addRegion(saveRegion(blobs, "region/r.0.0.mca", "chunk-v2"));
        store.saveSnapshot(target);

        Path archives = temp.resolve("archives");
        var creator = new ArchiveCreator(store, blobs, 1);
        Path fullTar = creator.createFull(baseEpoch, archives, false, 1);
        Path diffTar = creator.createDiff(baseEpoch, diffEpoch, archives, 1);

        Path conflictFull = archives.resolve("z-conflict-full.tar");
        Files.copy(fullTar, conflictFull);
        rewriteSnapshotWorld(conflictFull, "other-world");

        var verifier = new ArchiveVerifier();
        String expectedFull = ArchiveFormatUtil.expectedFullName(baseEpoch);

        ArchiveVerifier.Result conflictResult = verifier.verifyArchive(archives, diffTar);
        assertFalse(conflictResult.ok());
        String conflictText = String.join("\n", conflictResult.errors);
        assertTrue(conflictText.contains("Required baseline archive " + expectedFull + " is ambiguous"), conflictText);
        assertTrue(conflictText.contains(fullTar.getFileName().toString()), conflictText);
        assertTrue(conflictText.contains("z-conflict-full.tar"), conflictText);

        Files.delete(fullTar);
        Files.delete(conflictFull);

        ArchiveVerifier.Result missingResult = verifier.verifyArchive(archives, diffTar);
        assertFalse(missingResult.ok());
        String missingText = String.join("\n", missingResult.errors);
        assertTrue(missingText.contains("Required baseline archive " + expectedFull + " is missing"), missingText);
    }

    @Test
    void verifyVerifyLocatedAndVerifyAllHandleMultipleDiffs() throws IOException {
        Path storeDir = temp.resolve("store");
        var blobs = new BlobStore(storeDir, 1);
        var store = new SnapshotStore(storeDir);

        long baseEpoch = 1_000L;
        long diff1Epoch = 2_000L;
        long diff2Epoch = 3_000L;

        var base = new SnapshotManifest(SnapshotStore.generateSnapshotId(baseEpoch), baseEpoch, "world");
        base.addRegion(saveRegion(blobs, "region/r.0.0.mca", "chunk-v1"));
        store.saveSnapshot(base);

        var snap1 = new SnapshotManifest(SnapshotStore.generateSnapshotId(diff1Epoch), diff1Epoch, "world");
        snap1.addRegion(saveRegion(blobs, "region/r.0.0.mca", "chunk-v2"));
        store.saveSnapshot(snap1);

        var snap2 = new SnapshotManifest(SnapshotStore.generateSnapshotId(diff2Epoch), diff2Epoch, "world");
        snap2.addRegion(saveRegion(blobs, "region/r.0.0.mca", "chunk-v3"));
        store.saveSnapshot(snap2);

        Path archives = temp.resolve("archives");
        var creator = new ArchiveCreator(store, blobs, 1);
        Path fullTar = creator.createFull(baseEpoch, archives, false, 1);
        Path diff1Tar = creator.createDiff(baseEpoch, diff1Epoch, archives, 1);
        creator.createDiff(baseEpoch, diff2Epoch, archives, 1);

        var verifier = new ArchiveVerifier();

        ArchiveVerifier.Result byEpoch = verifier.verify(archives, baseEpoch, diff1Epoch);
        assertTrue(byEpoch.ok(), byEpoch.errors.toString());
        assertEquals(1, byEpoch.lrhChecked);
        assertEquals(1, byEpoch.lrhdChecked);

        ArchiveVerifier.Result all = verifier.verifyAll(archives, null);
        assertTrue(all.ok(), all.errors.toString());
        assertEquals(3, all.lrhChecked);
        assertEquals(2, all.lrhdChecked);

        // Even with a conflicting duplicate full in the directory, verifyLocated does not rescan.
        Path conflictFull = archives.resolve("z-conflict-full.tar");
        Files.copy(fullTar, conflictFull);
        rewriteSnapshotWorld(conflictFull, "other-world");

        ArchiveVerifier.Result located = verifier.verifyLocated(fullTar, diff1Tar, null);
        assertTrue(located.ok(), located.errors.toString());
        assertEquals(1, located.lrhChecked);
        assertEquals(1, located.lrhdChecked);
    }
}
