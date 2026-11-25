package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupCliDeleteAndListTest {

    @TempDir
    Path root;

    private static SnapshotManifest snapshot(String id, long epoch, Map<String, Long> sizes, String... blobPaths) {
        SnapshotManifest manifest = new SnapshotManifest(id, epoch, "world");
        long total = 0L;
        for (int i = 0; i < blobPaths.length; i++) {
            String path = blobPaths[i];
            long size = sizes.get(path);
            manifest.addExtraFile(new FileEntry("f" + i + "-" + path, "h-" + path, path, size, size * 2));
            total += size;
        }
        manifest.getSummary().setExtraFilesTotal(blobPaths.length);
        manifest.getSummary().setTotalCompressedBytes(total);
        manifest.getSummary().setTotalUncompressedBytes(total * 2);
        return manifest;
    }

    private String runCli(String... args) throws IOException {
        var out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true));
            BackupCli.execute(args);
        } finally {
            System.setOut(previous);
        }
        return out.toString();
    }

    private Path createSampleStore() throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        long mb = 1024L * 1024L;
        Map<String, Long> sizes = Map.of(
                "ALL", 100 * mb,
                "U1", 10 * mb,
                "U2", 20 * mb,
                "P12", 15 * mb,
                "U3", 5 * mb
        );
        store.saveSnapshot(snapshot("S-20260901_100000", 1000L, sizes, "ALL", "U1", "P12"));
        store.saveSnapshot(snapshot("S-20260901_110000", 2000L, sizes, "ALL", "U2", "P12"));
        store.saveSnapshot(snapshot("S-20260901_120000", 3000L, sizes, "ALL", "U3"));
        store.setPinned(3000L, true);
        return root;
    }

    @Test
    void snapshotListPrintsSummaryAndDynamicTable() throws Exception {
        Path storeRoot = createSampleStore();
        String output = runCli("--snapshot-root", storeRoot.toString(), "snapshot", "list");

        assertTrue(output.contains("Snapshots (3):"), output);
        assertTrue(output.contains("Stored:"), output);
        assertTrue(output.contains("Unique: 35.0M + Shared: 115.0M [All: 100.0M]"), output);
        assertTrue(output.contains("Pinned: 1 (Unique: 5.0M)"), output);
        assertTrue(output.contains("S-20260901_120000 [PIN]"), output);
        assertTrue(output.contains("Unique  Reclaim"), output);
        assertFalse(output.contains("[PINNED]"), output);
    }

    @Test
    void snapshotDeleteDryRunSupportsMixedArgOrderAndLeavesStoreUntouched() throws Exception {
        Path storeRoot = createSampleStore();
        String output = runCli("--snapshot-root", storeRoot.toString(),
                "snapshot", "delete", "--dry-run", "S-20260901_100000", "--yes", "S-20260901_110000", "S-20260901_100000");

        assertTrue(output.contains("Dry-run: snapshots to delete (2):"), output);
        assertTrue(output.contains("Individual unique sum:      30.0M"), output);
        assertTrue(output.contains("Shared only among selected: 15.0M"), output);
        assertTrue(output.contains("Estimated space to reclaim: 45.0M"), output);

        // Nothing deleted
        assertEquals(3, new SnapshotStore(storeRoot).loadAllSnapshots().size());
    }

    @Test
    void snapshotDeleteRejectsPinnedOrMissingWithoutPartialDeletion() throws Exception {
        Path storeRoot = createSampleStore();

        assertThrows(SnapshotStore.SnapshotPinnedException.class, () ->
                runCli("--snapshot-root", storeRoot.toString(),
                        "snapshot", "delete", "S-20260901_100000", "S-20260901_120000", "--yes"));
        assertEquals(3, new SnapshotStore(storeRoot).loadAllSnapshots().size());

        assertThrows(IOException.class, () ->
                runCli("--snapshot-root", storeRoot.toString(),
                        "snapshot", "delete", "S-20260901_100000", "S-20260901_999999", "--yes"));
        assertEquals(3, new SnapshotStore(storeRoot).loadAllSnapshots().size());

        assertThrows(IOException.class, () ->
                runCli("--snapshot-root", storeRoot.toString(),
                        "snapshot", "delete", "--dry-run"));
        assertThrows(IOException.class, () ->
                runCli("--snapshot-root", storeRoot.toString(),
                        "snapshot", "delete", "S-20260901_100000", "--unknown"));
    }

    @Test
    void snapshotDeleteWithYesAtomicallyDeletesMultipleSnapshots() throws Exception {
        Path storeRoot = createSampleStore();
        String output = runCli("--snapshot-root", storeRoot.toString(),
                "snapshot", "delete", "S-20260901_100000", "S-20260901_110000", "--yes");

        assertTrue(output.contains("Deleted 2 snapshot(s): S-20260901_100000, S-20260901_110000"), output);
        assertEquals(List.of("S-20260901_120000"), new SnapshotStore(storeRoot).listSnapshots());
    }
}
