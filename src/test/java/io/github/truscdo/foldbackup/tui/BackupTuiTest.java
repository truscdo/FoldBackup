package io.github.truscdo.foldbackup.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackupTuiTest {
    @TempDir
    Path root;

    @Test
    void createSnapshotRefreshesPreviouslyInitializedList() throws Exception {
        Path world = Files.createDirectory(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world data");
        Path store = root.resolve("store");
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            String input = "1\n5\n" + world + "\n\n1\n0\n";
            new BackupTui(store, store, new ConsolePrompt(new StringReader(input), System.out)).run();
        } finally { System.setOut(previous); }
        assertTrue(output.toString().contains("No snapshots found."));
        assertTrue(output.toString().contains("Created snapshot"));
        assertTrue(output.toString().contains("Snapshots (1):"), output.toString());
    }

    @Test
    void cancellingSnapshotCreationDoesNotInitializeStore() throws Exception {
        Path store = root.resolve("store");
        for (String input : new String[] {"5\n\n0\n", "5\n", "5\nworld\n0\n0\n", "5\nworld\n"}) {
            new BackupTui(store, store, new ConsolePrompt(new StringReader(input), System.out)).run();
            assertFalse(Files.exists(store));
        }
    }

    @Test
    void diffThreadSelectionValidatesInputAndReturnsToBaseSelection() throws Exception {
        Path online = Files.createDirectory(root.resolve("online-diff"));
        Path archive = Files.createDirectory(root.resolve("archive-diff"));
        var store = new io.github.truscdo.foldbackup.core.store.SnapshotStore(online);
        store.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest("S-20260917_100000", 1000, "world"));
        store.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest("S-20260917_110000", 2000, "world"));
        // Newest target, diff, base, back from threads, base again, invalid values, two threads, confirm.
        String input = "4\n1\n1\n1\n0\n1\nbad\n-1\n2\n1\n0\n";
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new BackupTui(online, archive, new ConsolePrompt(new StringReader(input), System.out)).run();
        } finally { System.setOut(previous); }
        assertTrue(output.toString().contains("Threads must be a positive integer."));
        assertTrue(output.toString().contains("Threads: 2"));
        assertTrue(Files.exists(archive.resolve("S-20260917_100000[S-20260917_110000].tar")), output.toString());
    }

    @Test
    void archiveCreationWritesIntoArchiveRoot() throws Exception {
        Path online = Files.createDirectory(root.resolve("online"));
        Path archive = Files.createDirectory(root.resolve("archive"));
        var store = new io.github.truscdo.foldbackup.core.store.SnapshotStore(online);
        store.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest(
                "S-20260914_120000", 1789358400L, "world"));
        // Create archive -> target -> full -> verbatim -> confirm -> exit.
        String input = "4\n1\n2\n2\n\n1\n0\n";
        new BackupTui(online, archive, new ConsolePrompt(new StringReader(input), System.out)).run();
        assertEquals(1, io.github.truscdo.foldbackup.core.archive.ArchiveTarScanner.fulls(
                io.github.truscdo.foldbackup.core.archive.ArchiveTarScanner.scan(archive)).size());
    }

    @Test
    void menuPlacesArchiveCreationUnderOnlineBackupsAndShowsNoDisabledEntries() throws Exception {
        Path online = Files.createDirectory(root.resolve("online-menu"));
        Path archive = Files.createDirectory(root.resolve("archive-menu"));
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new BackupTui(online, archive, new ConsolePrompt(new StringReader("0\n"), System.out)).run();
        } finally {
            System.setOut(previous);
        }

        String text = output.toString();
        int onlineMenu = text.indexOf("Online backups");
        int menuSeparator = text.indexOf("----------------------------------------", onlineMenu);
        int createArchive = text.indexOf("4. Create archive");
        int createSnapshot = text.indexOf("5. Create snapshot from world");
        assertTrue(onlineMenu >= 0 && createArchive > onlineMenu && createArchive < createSnapshot
                && createSnapshot < menuSeparator,
                "snapshot and archive creation belong to the online backups menu");
        assertTrue(text.contains("7. Import into online backup"));
        assertTrue(text.contains("8. Verify archives"));
        assertFalse(text.contains("[disabled]"), "both roots are always configured");
    }

    @Test
    void archiveOperationsNeverInitializeOnlineStoreUntilConfirmed() throws Exception {
        Path online = Files.createDirectory(root.resolve("online"));
        Path archive = Files.createDirectory(root.resolve("archive"));
        // Import cancelled at the confirmation prompt; verify an empty archive root.
        new BackupTui(online, archive, new ConsolePrompt(new StringReader("7\n0\n"), System.out)).run();
        new BackupTui(online, archive, new ConsolePrompt(new StringReader("8\n0\n"), System.out)).run();
        try (var entries = Files.list(online)) {
            assertEquals(0, entries.count());
        }
        try (var entries = Files.list(archive)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void invalidInputAndExitDoNotInitializeStore() throws Exception {
        var prompt = new ConsolePrompt(new StringReader("invalid\n9\n0\n"), System.out);
        new BackupTui(root, root, prompt).run();
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void selectingOnlineOperationInitializesStore() throws Exception {
        var prompt = new ConsolePrompt(new StringReader("1\n0\n"),
                new PrintStream(new ByteArrayOutputStream()));
        new BackupTui(root, root, prompt).run();
        try (var entries = Files.list(root)) {
            assertTrue(entries.findAny().isPresent(), "online operation must open the store");
        }
    }

    // ──────────────────────────────────────────────
    // 目录导出（检查 → 统计 → 决策 → 执行）
    // ──────────────────────────────────────────────

    /** Creates a store with one real snapshot: level.dat plus one Region file. */
    private Path createSnapshotStore() throws Exception {
        Path world = Files.createDirectory(root.resolve("export-source"));
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        var region = new io.github.truscdo.foldbackup.core.format.Region(0, 0, 0);
        region.setChunk(0, new io.github.truscdo.foldbackup.core.format.ChunkData(new byte[] {1, 2, 3}, 0, 0));
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(
                world.resolve("region/r.0.0.mca"), region, 6);
        Path store = root.resolve("export-store");
        io.github.truscdo.foldbackup.cli.SnapshotCreation.create(world, store, 1, event -> { });
        return store;
    }

    private String runTui(Path store, String input) {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new BackupTui(store, root.resolve("export-archive"),
                    new ConsolePrompt(new StringReader(input), System.out)).run();
        } finally {
            System.setOut(previous);
        }
        return output.toString();
    }

    @Test
    void exportDirectoryShowsCheckProgressAndWritesSave() throws Exception {
        Path store = createSnapshotStore();
        Path output = root.resolve("exported-world");

        String text = runTui(store, "2\n1\n2\n" + output + "\n1\n\n0\n");

        assertTrue(Files.isRegularFile(output.resolve("level.dat")), text);
        assertTrue(Files.isRegularFile(output.resolve("region/r.0.0.mca")), text);
        assertTrue(text.contains("Checking"), "the check phase reports progress: " + text);
        assertTrue(text.contains("Changes:  1 region(s), 1 file(s)"), text);
        assertTrue(text.contains("Done: "), text);
    }

    @Test
    void blankAtDecisionReturnsToPathWithoutExporting() throws Exception {
        Path store = createSnapshotStore();
        Path output = root.resolve("not-exported");

        String text = runTui(store, "2\n1\n2\n" + output + "\n\n0\n0\n0\n");

        assertFalse(Files.exists(output), text);
        assertTrue(text.contains("Target:"), text);
        assertFalse(text.contains("Done: "), text);
    }

    @Test
    void decisionTwoDeletesUnlistedContentAndDecisionOneKeepsIt() throws Exception {
        Path store = createSnapshotStore();
        Path output = root.resolve("existing-world");
        runTui(store, "2\n1\n2\n" + output + "\n1\n\n0\n");

        Path extra = output.resolve("data/extra.txt");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "extra");

        String deleted = runTui(store, "2\n1\n2\n" + output + "\n2\n\n0\n");
        assertFalse(Files.exists(extra), deleted);
        assertTrue(deleted.contains("Deletion: 1 candidate(s)"), deleted);
        assertTrue(deleted.contains("deleted extra: true"), deleted);

        Path kept = output.resolve("data-kept/keep.txt");
        Files.createDirectories(kept.getParent());
        Files.writeString(kept, "keep");
        String keeping = runTui(store, "2\n1\n2\n" + output + "\n1\n\n0\n");
        assertTrue(Files.exists(kept), keeping);
        assertTrue(keeping.contains("deleted extra: false"), keeping);
    }
}
