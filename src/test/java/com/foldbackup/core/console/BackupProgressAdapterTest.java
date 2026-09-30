package com.foldbackup.core.console;

import com.foldbackup.core.backup.BackupStep;
import com.foldbackup.core.backup.ScanStats;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupProgressAdapterTest {
    @Test
    void growingDiscoveryCountIsNotTreatedAsFinalTotal() {
        var step = new BackupStep.ScanningWorld(1, 2, "region/r.0.0.mca", "level.dat", false, 5, 7, 1);
        var event = BackupProgressAdapter.toEvent(step);
        assertEquals(4, event.completed());
        assertEquals(-1, event.total());
        assertTrue(event.item().contains("1 failed"));
        assertTrue(event.item().contains("last discovered region: region/r.0.0.mca"));
        assertTrue(event.item().contains("last discovered file: level.dat"));
    }

    @Test
    void finishedScanStillDisplaysFailures() {
        var event = BackupProgressAdapter.toEvent(new BackupStep.ScanningWorld(1, 2, "", "", true, 2, 2, 1));
        assertEquals(4, event.completed());
        assertEquals(4, event.total());
        assertEquals("1 failed", event.item());
    }

    @Test
    void longPathsDoNotHideFailureCountInTerminalOutput() {
        var event = BackupProgressAdapter.toEvent(new BackupStep.ScanningWorld(
                0, 1, "", "directory/".repeat(20) + "level.dat", false, 0, 2, 1));
        var output = new ByteArrayOutputStream();
        try (var printer = new ProgressPrinter(new PrintStream(output))) {
            printer.print(event);
        }
        assertTrue(output.toString().contains("1 failed"), output.toString());
    }

    @Test
    void completionPreservesSmallSizesAndCleanupFailureIsDistinct() {
        var complete = BackupProgressAdapter.toEvent(new BackupStep.Completed(1, "snapshot", new ScanStats(0, 0, 1, 1), 73));
        assertTrue(complete.item().contains("73 B"));
        var cleanup = BackupProgressAdapter.toEvent(new BackupStep.CleanupFailed(1, "snapshot", "disk error"));
        assertEquals("Snapshot committed; cleanup failed", cleanup.phase());
        assertTrue(cleanup.item().contains("snapshot"));
        assertTrue(cleanup.item().contains("disk error"));
        assertEquals(-1, cleanup.total());
    }
}
