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
        assertTrue(out.contains("Verified 3 .lrh, 1 .lrhd, 42 chunk hashes, 1.0K total"));
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
    void selectionTable_hasIdDateSizeUniqueColumnsAndDynamicWidth() {
        var c = capture();
        var snaps = List.of(new BackupExporter.SnapshotInfo(
                1000L, "S-20260828_100000", "2026-08-28 10:00:00", 5, 2, 1000, 4000, 250, 1000, true));

        SnapshotTablePrinter.printSelection(snaps, c.stream());
        String out = c.output();
        String[] lines = out.strip().split("\\R");
        assertEquals(3, lines.length);
        assertEquals(lines[0].length(), lines[1].length(), "separator must match header width");
        assertTrue(lines[0].contains("#"));
        assertTrue(lines[0].contains("ID"));
        assertTrue(lines[0].contains("Date"));
        assertTrue(lines[0].contains("Size"));
        assertTrue(lines[0].contains("Unique"));
        assertTrue(lines[2].contains("S-20260828_100000 [PIN]"));
        assertTrue(lines[2].contains("1000B"));
        assertTrue(lines[2].contains("250B"));
        assertFalse(out.contains("Regions"), "selection table must not have Regions column");
        assertFalse(out.contains("[PINNED]"), "selection table must use compact [PIN] tag");
    }

    @Test
    void listTable_hasRegionsFilesUniqueReclaimColumnsAndDynamicWidth() {
        var c = capture();
        var snaps = List.of(
                new BackupExporter.SnapshotInfo(
                        2000L, "S-20260828_110000", "2026-08-28 11:00:00", 5, 2, 1000, 4000, 200, 600, false),
                new BackupExporter.SnapshotInfo(
                        1000L, "S-20260828_100000", "2026-08-28 10:00:00", 5, 2, 1000, 4000, 300, 0, true));

        SnapshotTablePrinter.printList(snaps, c.stream());
        String out = c.output();
        String[] lines = out.strip().split("\\R");
        assertEquals(4, lines.length);
        assertEquals(lines[0].length(), lines[1].length(), "separator must match header width");
        assertTrue(lines[0].contains("Regions"));
        assertTrue(lines[0].contains("Files"));
        assertTrue(lines[0].contains("Unique"));
        assertTrue(lines[0].contains("Reclaim"));
        assertFalse(out.contains("Cumulative Space"));
        assertTrue(lines[2].contains("200B"));
        assertTrue(lines[2].contains("600B"));
        assertTrue(lines[3].contains("S-20260828_100000 [PIN]"));
        assertTrue(lines[3].endsWith("-"));
    }

    @Test
    void printSummary_formatsPinnedAndUnpinnedVariants() {
        var c1 = capture();
        var withPin = new io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator.RepositorySpaceSummary(
                1024L * 1024 * 100, 1024L * 128, 1024L * 1024 * 20, 1024L * 1024 * 80,
                1024L * 1024 * 70, 1024L * 1024 * 10, 1024L * 1024 * 200, 1, 1024L * 1024 * 15);
        SnapshotTablePrinter.printSummary(withPin, c1.stream());
        assertTrue(c1.output().contains("Pinned: 1 (Held: 15.0M)"), c1.output());
        assertTrue(c1.output().contains("[All: 70.0M]"), c1.output());
        assertTrue(c1.output().contains("saved 50.0%"), c1.output());

        var c2 = capture();
        var withoutPin = new io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator.RepositorySpaceSummary(
                1024L * 1024 * 100, 0L, 1024L * 1024 * 20, 1024L * 1024 * 80,
                1024L * 1024 * 70, 1024L * 1024 * 10, 1024L * 1024 * 200, 0, 0L);
        SnapshotTablePrinter.printSummary(withoutPin, c2.stream());
        assertTrue(c2.output().trim().endsWith("Pinned: 0"), c2.output());
    }
}
