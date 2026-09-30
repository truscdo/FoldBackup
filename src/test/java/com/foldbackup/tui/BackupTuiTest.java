package com.foldbackup.tui;

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
            new BackupTui(store, null, new ConsolePrompt(new StringReader(input), System.out)).run();
        } finally { System.setOut(previous); }
        assertTrue(output.toString().contains("No snapshots found."));
        assertTrue(output.toString().contains("Created snapshot"));
        assertTrue(output.toString().contains("Snapshots (1):"), output.toString());
    }

    @Test
    void cancellingSnapshotCreationDoesNotInitializeStore() throws Exception {
        Path store = root.resolve("store");
        for (String input : new String[] {"5\n\n0\n", "5\n", "5\nworld\n0\n0\n", "5\nworld\n"}) {
            new BackupTui(store, null, new ConsolePrompt(new StringReader(input), System.out)).run();
            assertFalse(Files.exists(store));
        }
    }

    @Test
    void diffThreadSelectionValidatesInputAndReturnsToBaseSelection() throws Exception {
        Path online = Files.createDirectory(root.resolve("online-diff"));
        Path archive = Files.createDirectory(root.resolve("archive-diff"));
        var store = new com.foldbackup.core.store.SnapshotStore(online);
        store.saveSnapshot(new com.foldbackup.core.store.SnapshotManifest("S-20260917_100000", 1000, "world"));
        store.saveSnapshot(new com.foldbackup.core.store.SnapshotManifest("S-20260917_110000", 2000, "world"));
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
    void onlineRootIsUsedForArchiveCreationWhenArchiveRootIsMissing() throws Exception {
        Path online = Files.createDirectory(root.resolve("online"));
        var store = new com.foldbackup.core.store.SnapshotStore(online);
        store.saveSnapshot(new com.foldbackup.core.store.SnapshotManifest(
                "S-20260914_120000", 1789358400L, "world"));
        // Create archive -> target -> full -> verbatim -> confirm -> exit.
        String input = "4\n1\n2\n2\n\n1\n0\n";
        new BackupTui(online, null, new ConsolePrompt(new StringReader(input), System.out)).run();
        assertEquals(1, com.foldbackup.core.archive.ArchiveTarScanner.fulls(
                com.foldbackup.core.archive.ArchiveTarScanner.scan(online)).size());
    }

    @Test
    void menuPlacesArchiveCreationUnderOnlineBackupsAndKeepsImportEnabledWithoutOnlineRoot() throws Exception {
        Path archive = Files.createDirectory(root.resolve("archive"));
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new BackupTui(null, archive, new ConsolePrompt(new StringReader("0\n"), System.out)).run();
        } finally {
            System.setOut(previous);
        }

        String text = output.toString();
        int onlineMenu = text.indexOf("Online backups");
        int menuSeparator = text.indexOf("----------------------------------------", onlineMenu);
        int createArchive = text.indexOf("4. Create archive");
        int createSnapshot = text.indexOf("5. Create snapshot from world [disabled]");
        assertTrue(onlineMenu >= 0 && createArchive > onlineMenu && createArchive < createSnapshot
                && createSnapshot < menuSeparator,
                "snapshot and archive creation belong to the online backups menu");
        assertTrue(text.contains("7. Import into online backup"));
        assertFalse(text.contains("7. Import into online backup [disabled]"));
        assertTrue(text.contains("8. Verify archives"));
    }

    @Test
    void archiveOnlyAndCombinedArchiveOperationsNeverInitializeOnlineStore() throws Exception {
        Path online = Files.createDirectory(root.resolve("online"));
        Path archive = Files.createDirectory(root.resolve("archive"));
        new BackupTui(null, archive, new ConsolePrompt(new StringReader("7\n0\n"), System.out)).run();
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
        new BackupTui(root, null, prompt).run();
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void selectingOnlineOperationInitializesStore() throws Exception {
        var prompt = new ConsolePrompt(new StringReader("1\n0\n"),
                new PrintStream(new ByteArrayOutputStream()));
        new BackupTui(root, null, prompt).run();
        try (var entries = Files.list(root)) {
            assertTrue(entries.findAny().isPresent(), "online operation must open the store");
        }
    }
}
