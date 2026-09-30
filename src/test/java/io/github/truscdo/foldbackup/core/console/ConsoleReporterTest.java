package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.archive.ArchiveVerifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ArchiveVerifyReporter} / {@link SnapshotTablePrinter} 单元测试：
 * 统一文本输出的格式与语义。
 */
class ConsoleReporterTest {

    private record Captured(ByteArrayOutputStream buffer, PrintStream stream) {
        String output() {
            return buffer.toString();
        }
    }

    private static Captured capture() {
        var buf = new ByteArrayOutputStream();
        return new Captured(buf, new PrintStream(buf, true));
    }

    // ── ArchiveVerifyReporter ──

    @Test
    void verifyReport_ok() {
        var c = capture();
        ArchiveVerifier.Result r = new ArchiveVerifier.Result();
        r.lrhChecked = 3;
        r.lrhdChecked = 1;
        r.chunkHashesChecked = 42;
        r.totalBytes = 1024;

        ArchiveVerifyReporter.print(r, c.stream());
        String out = c.output();
        assertTrue(out.contains("Verified 3 .lrh, 1 .lrhd, 42 chunk hashes"));
        assertTrue(out.contains("OK — no errors"));
        assertFalse(out.contains("FAILED"));
    }

    @Test
    void verifyReport_failedListsErrors() {
        var c = capture();
        ArchiveVerifier.Result r = new ArchiveVerifier.Result();
        r.errors.addAll(List.of("hash mismatch: a.lrh", "missing files.bin"));

        ArchiveVerifyReporter.print(r, c.stream());
        String out = c.output();
        assertTrue(out.contains("FAILED — 2 error(s):"));
        assertTrue(out.contains("  hash mismatch: a.lrh"));
        assertTrue(out.contains("  missing files.bin"));
    }

    // ── SnapshotTablePrinter ──

    @Test
    void selectionTable_hasIdDateSizeColumns() {
        var c = capture();
        var snaps = List.of(new BackupExporter.SnapshotInfo(
                "snap-1", "2026-08-28 10:00:00", 5, 2, 1000, 4000));

        SnapshotTablePrinter.printSelection(snaps, c.stream());
        String out = c.output();
        assertTrue(out.contains("#"));
        assertTrue(out.contains("ID"));
        assertTrue(out.contains("Date"));
        assertTrue(out.contains("Size"));
        assertTrue(out.contains("snap-1"));
        assertFalse(out.contains("Regions"), "selection table must not have Regions column");
    }

    @Test
    void listTable_hasRegionsFilesColumns() {
        var c = capture();
        var snaps = List.of(new BackupExporter.SnapshotInfo(
                "snap-1", "2026-08-28 10:00:00", 5, 2, 1000, 4000));

        SnapshotTablePrinter.printList(snaps, c.stream());
        String out = c.output();
        assertTrue(out.contains("Regions"));
        assertTrue(out.contains("Files"));
        assertTrue(out.contains("Cumulative Space"));
        assertTrue(out.contains("snap-1"));
    }
}
