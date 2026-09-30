package com.foldbackup.tui;

import com.foldbackup.core.archive.ArchiveSnapshot;
import com.foldbackup.core.archive.ArchiveSnapshotCodec;
import com.foldbackup.core.archive.TarWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveTuiTest {

    @TempDir
    Path archive;

    @Test
    void selectingDiffAutomaticallyUsesMatchingFull() throws Exception {
        writeTar("S-20260914_120000[full].tar", full(100, "S-20260914_120000"));
        writeTar("S-20260914_120000[S-20260914_130000].tar",
                diff(200, 100, "S-20260914_130000"));

        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new ArchiveTui(archive, null, new ConsolePrompt(new StringReader("2\n"),
                    new PrintStream(output, true))).verifyArchives();
        } finally {
            System.setOut(previous);
        }

        String text = output.toString();
        assertTrue(text.contains("diff"));
        assertTrue(text.contains("OK — no errors"));
    }

    @Test
    void selectingDiffWithoutFullExplainsHowToRecover() throws Exception {
        writeTar("S-20260914_120000[S-20260914_130000].tar",
                diff(200, 100, "S-20260914_130000"));

        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true));
            new ArchiveTui(archive, null, new ConsolePrompt(new StringReader("1\n"),
                    new PrintStream(output, true))).verifyArchives();
        } finally {
            System.setOut(previous);
        }

        assertTrue(output.toString().contains("Put the full archive"));
    }

    @Test
    void restoreUsesTargetSnapshotInDefaultOutputPath() throws Exception {
        writeTar("S-20260914_120000[full].tar", full(100, "S-20260914_120000"));

        var output = new ByteArrayOutputStream();
        // Select the full, accept the default path, then cancel at the summary.
        new ArchiveTui(archive, null, new ConsolePrompt(new StringReader("1\n\n2\n"),
                new PrintStream(output, true))).exportArchive();

        assertTrue(output.toString().contains("world-export"));
        assertTrue(output.toString().contains("S-20260914_120000"));
    }

    @Test
    void importFallsBackToArchiveRootWhenOnlineRootIsMissing() throws Exception {
        String snapshotId = "S-20260914_120000";
        writeTar(snapshotId + "[full].tar", full(100, snapshotId));

        new ArchiveTui(archive, null, new ConsolePrompt(new StringReader("1\n\n"),
                new PrintStream(new ByteArrayOutputStream(), true))).importArchives();

        var imported = new com.foldbackup.core.store.SnapshotStore(archive).loadSnapshot(100);
        assertTrue(imported != null, "archive-only import should rebuild the online index in archive-root");
    }

    @Test
    void importThreadBackReturnsToConfirmationAndCanCancelOrProceed() throws Exception {
        writeTar("full.tar", full(100, "S-20260914_120000"));
        Path target = archive.resolve("online");
        new ArchiveTui(archive, target, new ConsolePrompt(new StringReader("1\n0\n2\n"), System.out))
                .importArchives();
        assertFalse(Files.exists(target));
        new ArchiveTui(archive, target, new ConsolePrompt(new StringReader("1\n0\n1\n\n"), System.out))
                .importArchives();
        assertNotNull(new com.foldbackup.core.store.SnapshotStore(target).loadSnapshot(100));
    }

    @Test
    void restoreThreadBackKeepsOutputAndReturnsToConfirmation() throws Exception {
        writeTar("full.tar", full(100, "S-20260914_120000"));
        Path target = archive.resolve("world");
        String selection = "1\n" + target + "\n1\n0\n";
        new ArchiveTui(archive, null, new ConsolePrompt(new StringReader(selection + "2\n"), System.out))
                .exportArchive();
        assertFalse(Files.exists(target));
        new ArchiveTui(archive, null, new ConsolePrompt(new StringReader(selection + "1\n\n"), System.out))
                .exportArchive();
        assertTrue(Files.isDirectory(target));
    }

    private void writeTar(String name, ArchiveSnapshot metadata) throws Exception {
        try (var output = Files.newOutputStream(archive.resolve(name));
                var writer = new TarWriter(output)) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(metadata));
        }
    }

    private static ArchiveSnapshot full(long epoch, String sourceId) {
        ArchiveSnapshot metadata = new ArchiveSnapshot();
        metadata.type = "full";
        metadata.epoch = epoch;
        metadata.sourceSnapshotId = sourceId;
        return metadata;
    }

    private static ArchiveSnapshot diff(long epoch, long baseEpoch, String sourceId) {
        ArchiveSnapshot metadata = new ArchiveSnapshot();
        metadata.type = "diff";
        metadata.epoch = epoch;
        metadata.baseEpoch = baseEpoch;
        metadata.sourceSnapshotId = sourceId;
        return metadata;
    }
}
