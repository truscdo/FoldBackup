package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressPrinterTest {

    @Test
    void throttlesWithinStageAndRendersStageChangesAndCompletion() {
        var bytes = new ByteArrayOutputStream();
        var clock = new AtomicLong();
        try (var printer = new ProgressPrinter(new PrintStream(bytes), clock::get)) {
            var callback = printer.onProgress();
            callback.onProgress(new ProgressEvent("Copying files", 0, 3, ""));
            callback.onProgress(new ProgressEvent("Copying files", 0, 3, "a.txt"));
            int before = bytes.size();
            callback.onProgress(new ProgressEvent("Copying files", 1, 3, "a.txt"));
            assertEquals(before, bytes.size());

            clock.set(2_000_000_000L);
            callback.onProgress(new ProgressEvent("Copying files", 2, 3, "b.txt"));
            callback.onProgress(new ProgressEvent("Copying files", 3, 3, "c.txt"));
            callback.onProgress(new ProgressEvent("Publishing", 0, 1, "world"));
        }

        String output = bytes.toString();
        assertTrue(output.contains("2/3"));
        assertTrue(output.contains("[100%] Copying files 3/3"));
        assertTrue(output.contains("Publishing 0/1"));
        assertTrue(output.endsWith(System.lineSeparator()));
    }

    @Test
    void rendersUnknownTotalsAndSanitizesItems() {
        var bytes = new ByteArrayOutputStream();
        try (var printer = new ProgressPrinter(new PrintStream(bytes))) {
            printer.print(new ProgressEvent("Verifying", 1, -1, "bad\nname"));
        }
        assertTrue(bytes.toString().contains("[ --%] Verifying 1/?"));
        assertTrue(bytes.toString().contains("bad?name"));
    }
}
