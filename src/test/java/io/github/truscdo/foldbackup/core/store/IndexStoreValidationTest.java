package io.github.truscdo.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IndexStoreValidationTest {
    @TempDir Path root;

    static SnapshotManifest snapshot(int n) {
        SnapshotManifest sn = new SnapshotManifest("S-20260907_1300%02d".formatted(n), n, "world");
        sn.addExtraFile(new FileEntry("file", "hash", "blobs/live", 1, 2));
        return sn;
    }

    private byte[] saved() throws Exception {
        new SnapshotStore(root).saveSnapshot(snapshot(0));
        return Files.readAllBytes(root.resolve("index.db"));
    }

    private IOException rejects(byte[] bytes) throws Exception {
        Files.write(root.resolve("index.db"), bytes);
        IOException error = assertThrows(IOException.class, () -> {
            try (IndexStore disk = new IndexStore(root.resolve("index.db"))) {
                disk.load();
            }
        });
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().isBlank());
        return error;
    }

    @Test void everyTruncationIsAnIOExceptionWithContext() throws Exception {
        byte[] valid = saved();
        for (int length = 0; length < valid.length; length++) rejects(Arrays.copyOf(valid, length));
        rejects(Arrays.copyOf(valid, valid.length + 1));
    }

    @Test void rejectsNegativeAndImpossibleCountsAndRecordLengths() throws Exception {
        byte[] valid = saved();
        int snapshotCountOffset = 13 + ByteBuffer.wrap(valid).getInt(9);
        for (int offset : new int[]{5, 9, snapshotCountOffset, valid.length - 36}) {
            for (int value : new int[]{-1, Integer.MAX_VALUE}) {
                byte[] bad = valid.clone();
                ByteBuffer.wrap(bad).putInt(offset, value);
                rejects(bad);
            }
        }
    }

    @Test void rejectsUnknownTypeMissingReferencesAndDuplicateRecords() throws Exception {
        byte[] valid = saved();
        byte[] bad = valid.clone();
        bad[13] = 2;
        assertTrue(rejects(bad).getMessage().contains("metadata.type"));
        bad = valid.clone();
        Arrays.fill(bad, bad.length - 32, bad.length, (byte)'0');
        assertTrue(rejects(bad).getMessage().contains("reference"));
        int endMetadata = 13 + ByteBuffer.wrap(valid).getInt(9);
        ByteArrayOutputStream doubled = new ByteArrayOutputStream();
        doubled.write(valid, 0, endMetadata + 4);
        doubled.write(valid, endMetadata + 4, valid.length - endMetadata - 4);
        doubled.write(valid, endMetadata + 4, valid.length - endMetadata - 4);
        bad = doubled.toByteArray();
        ByteBuffer.wrap(bad).putInt(endMetadata, 2);
        assertTrue(rejects(bad).getMessage().contains("duplicate"));
        doubled.reset();
        doubled.write(valid, 0, endMetadata);
        doubled.write(valid, 9, endMetadata - 9);
        doubled.write(valid, endMetadata, valid.length - endMetadata);
        bad = doubled.toByteArray();
        ByteBuffer.wrap(bad).putInt(5, 2);
        assertTrue(rejects(bad).getMessage().contains("duplicate metadata"));
    }

    @Test void metadataRejectsInvalidEncodingLengthsSizesAndTrailingBytes() throws Exception {
        EntryMetadata entry = new EntryMetadata(EntryMetadata.Type.FILE, "f", "hash", "blob", 1, 2,
                SourceMetadata.empty());
        byte[] valid = entry.toBytes();
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> EntryMetadata.fromBytes(truncated));
        }
        byte[] bad = valid.clone();
        bad[3] = (byte) 0xff;
        assertTrue(assertThrows(IOException.class, () -> EntryMetadata.fromBytes(bad)).getMessage().contains("filename"));
        byte[] negative = valid.clone();
        Arrays.fill(negative, negative.length - 8, negative.length, (byte) 0xff);
        assertThrows(IOException.class, () -> EntryMetadata.fromBytes(negative));
        assertThrows(IOException.class, () -> EntryMetadata.fromBytes(Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test void metadataEncodingRejectsEveryShortFieldOverflowAndLossyText() {
        String oversized = "x".repeat(65536);
        for (int field = 0; field < 3; field++) {
            String[] values = {"file", "hash", "blob"};
            values[field] = oversized;
            EntryMetadata entry = new EntryMetadata(EntryMetadata.Type.REGION,
                    values[0], values[1], values[2], 1, 2, SourceMetadata.empty());
            assertThrows(IllegalArgumentException.class, entry::toBytes);
        }
        assertThrows(NullPointerException.class, () -> new EntryMetadata(
                null, "file", "hash", "blob", 1, 2, SourceMetadata.empty()).toBytes());
        assertThrows(IllegalArgumentException.class, () -> new EntryMetadata(
                EntryMetadata.Type.FILE, "file", "非ASCII", "blob", 1, 2, SourceMetadata.empty()).toBytes());
        assertThrows(IllegalArgumentException.class, () -> new EntryMetadata(
                EntryMetadata.Type.FILE, "\uD800", "hash", "blob", 1, 2, SourceMetadata.empty()).toBytes());
    }

    @Test void unsignedShortBoundariesRoundTripAndOverflowPreservesOldIndex() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        SnapshotManifest boundary = snapshot(0);
        boundary.setWorldName("界".repeat(21845)); // 65535 UTF-8 bytes
        boundary.setTags(new ArrayList<>(Collections.nCopies(65535, "")));
        store.saveSnapshot(boundary);
        assertEquals(boundary.getWorldName(), new SnapshotStore(root).loadSnapshot(boundary.getEpochSeconds()).getWorldName());
        byte[] old = Files.readAllBytes(root.resolve("index.db"));
        for (int variant = 0; variant < 4; variant++) {
            SnapshotManifest bad = snapshot(1);
            if (variant == 0) bad.setWorldName("界".repeat(21846));
            if (variant == 1) bad.setTags(new ArrayList<>(Collections.nCopies(65536, "")));
            if (variant == 2) bad.getTags().add("x".repeat(65536));
            if (variant == 3) bad.addExtraFile(new FileEntry("x".repeat(65536), "hash", "blob", 1, 1));
            assertThrows(IOException.class, () -> store.saveSnapshot(bad));
            assertArrayEquals(old, Files.readAllBytes(root.resolve("index.db")));
            assertEquals(1, store.listSnapshots().size());
        }
        EntryMetadata max = new EntryMetadata(EntryMetadata.Type.FILE, "x".repeat(65535), "hash", "b", 0, 0,
                SourceMetadata.empty());
        assertEquals(max.getFilename(), EntryMetadata.fromBytes(max.toBytes()).getFilename());
    }

    @Test void malformedSnapshotUtf8AndNegativeSummaryAreRejected() throws Exception {
        byte[] valid = saved();
        int snapshotStart = 17 + ByteBuffer.wrap(valid).getInt(9);
        byte[] bad = valid.clone();
        bad[snapshotStart + 2] = (byte) 0xff;
        assertTrue(rejects(bad).getMessage().contains("snapshotId"));
        bad = valid.clone();
        ByteBuffer.wrap(bad).putInt(bad.length - 36 - 40, -1);
        assertTrue(rejects(bad).getMessage().contains("summary"));
    }

    @Test void validationRejectsDuplicateIdsAndMissingReferencesBeforeOpeningTemp() throws Exception {
        byte[] old = saved();
        try (IndexStore disk = new IndexStore(root.resolve("index.db"))) {
            MetadataIndex index = disk.load();
            assertThrows(IllegalArgumentException.class, () -> index.addSnapshot(snapshot(0), List.of()));
            MetadataIndex missing = new MetadataIndex();
            missing.addSnapshot(snapshot(1), List.of("0".repeat(32)));
            assertThrows(IOException.class, () -> disk.save(missing));
        }
        assertArrayEquals(old, Files.readAllBytes(root.resolve("index.db")));
        assertFalse(Files.exists(root.resolve("index.db.tmp")));
    }

    @Test void loadingIndexRejectsDuplicateEpochs() throws Exception {
        MetadataIndex index = new MetadataIndex();
        index.addSnapshot(new SnapshotManifest("S-20260907_130000", 100, "world"), List.of());
        index.addSnapshot(new SnapshotManifest("S-20260907_130001", 101, "world"), List.of());
        try (IndexStore disk = new IndexStore(root.resolve("index.db"))) {
            disk.save(index);
        }
        byte[] bytes = Files.readAllBytes(root.resolve("index.db"));
        int secondEpochOffset = secondSnapshotEpochOffset(bytes);
        // IndexStore persists snapshots newest first, so the first epoch is 101.
        ByteBuffer.wrap(bytes).putLong(secondEpochOffset, 101);

        IOException error = rejects(bytes);
        assertTrue(error.getMessage().contains("duplicate epoch"));
    }

    private static int secondSnapshotEpochOffset(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.skipNBytes(5); // magic + version
            assertEquals(0, in.readInt()); // metadata_count
            assertEquals(2, in.readInt()); // snapshot_count
            skipSnapshot(in);
            skipString(in); // second snapshot ID
            return bytes.length - in.available();
        }
    }

    private static void skipSnapshot(DataInputStream in) throws IOException {
        skipString(in);
        in.skipNBytes(8); // epoch seconds
        skipString(in); // world name
        skipString(in); // Minecraft version
        in.skipNBytes(8); // world version
        int tagCount = in.readUnsignedShort();
        for (int i = 0; i < tagCount; i++) skipString(in);
        in.skipNBytes(40); // summary
        int entryCount = in.readInt();
        in.skipNBytes((long) entryCount * 32);
    }

    private static void skipString(DataInputStream in) throws IOException {
        in.skipNBytes(in.readUnsignedShort());
    }

    @Test void forceAndAtomicMoveFailuresPreserveDiskMemoryAndBlobs() throws Exception {
        Files.createDirectories(root.resolve("blobs"));
        Files.writeString(root.resolve("blobs/live"), "live");
        byte[] old = saved();
        for (boolean failForce : new boolean[]{true, false}) {
            IndexStore failing = new IndexStore(root.resolve("index.db")) {
                @Override void forceTemporary(FileChannel channel) throws IOException {
                    // flush must have exposed a complete staged deletion before force.
                    try (IndexStore staged = new IndexStore(root.resolve("index.db.tmp"))) {
                        assertEquals(0, staged.load().snapshotCount());
                    }
                    if (failForce) throw new IOException("force failure");
                    super.forceTemporary(channel);
                }
                @Override void replaceTemporary() throws IOException {
                    throw new AtomicMoveNotSupportedException("tmp", "index", "test");
                }
            };
            SnapshotStore store = new SnapshotStore(root, failing);
            assertThrows(IOException.class, () -> store.deleteSnapshot(snapshot(0).getEpochSeconds()));
            assertArrayEquals(old, Files.readAllBytes(root.resolve("index.db")));
            assertEquals(1, store.listSnapshots().size());
            assertTrue(Files.exists(root.resolve("blobs/live")));
        }
    }

    @Test void staleTempNeverOverridesValidMissingOrCorruptIndex() throws Exception {
        byte[] valid = saved();
        Files.write(root.resolve("index.db.tmp"), valid);
        Files.delete(root.resolve("index.db"));
        assertEquals(0, new SnapshotStore(root).listSnapshots().size());
        rejects(new byte[]{0});
        Files.write(root.resolve("index.db"), valid);
        Files.writeString(root.resolve("index.db.tmp"), "partial");
        SnapshotStore store = new SnapshotStore(root);
        assertEquals(1, store.listSnapshots().size());
        store.saveSnapshot(snapshot(1));
        assertFalse(Files.exists(root.resolve("index.db.tmp")));
        assertEquals(2, new SnapshotStore(root).listSnapshots().size());
    }

    @Test void processExitBeforeAndAfterAtomicReplaceRecoversCompleteIndexAndGc() throws Exception {
        Files.createDirectories(root.resolve("blobs"));
        Files.writeString(root.resolve("blobs/live"), "live");
        saved();
        String classpath = System.getProperty("foldbackup.testClasspath");
        for (String phase : List.of("before", "after")) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, IndexCrashProcess.class.getName(), root.toString(), phase)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                assertEquals(23, child.exitValue());
            } finally { child.destroyForcibly(); }
            SnapshotStore recovered = new SnapshotStore(root);
            assertEquals(phase.equals("before") ? 1 : 2, recovered.listSnapshots().size());
            recovered.collectGarbage();
            assertTrue(Files.exists(root.resolve("blobs/live")));
            assertEquals(phase.equals("before"), Files.exists(root.resolve("index.db.tmp")));
        }
    }
}
