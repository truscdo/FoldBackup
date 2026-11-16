package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.console.ProgressPrinterAccess;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleOutputCoordinatorTest {

    @Test
    void logWriteClearsActiveProgressLinePrintsLogAndRestoresProgress() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var clock = new AtomicLong(0L);
        var coordinator = new ConsoleOutputCoordinator();

        try (var printer = ProgressPrinterAccess.create(stream, clock::get, coordinator, false, 0L)) {
            printer.print(new ProgressEvent("Compressing", 1, 10, "r.0.0.mca"));
            clock.set(2_000_000_000L);
            coordinator.writeLog(stream, "[WRN] source changed", null);
        }

        String text = bytes.toString(StandardCharsets.UTF_8);
        String[] lines = text.split("\\R");
        boolean hasCleanLogLine = false;
        boolean hasRestoredProgress = false;
        for (String line : lines) {
            if (line.contains("[WRN] source changed")) {
                // 日志行不得拼接在未换行的进度条尾巴后面
                assertFalse(line.contains("Compressing"), "log line was interleaved with progress: " + line);
                hasCleanLogLine = true;
            }
            if (line.contains("Compressing 1/10 | 2s | r.0.0.mca")) {
                hasRestoredProgress = true;
            }
        }
        assertTrue(hasCleanLogLine, text);
        assertTrue(hasRestoredProgress, text);
    }
}
