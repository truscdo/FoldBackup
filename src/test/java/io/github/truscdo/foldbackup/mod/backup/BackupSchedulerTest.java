package io.github.truscdo.foldbackup.mod.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BackupSchedulerTest {

    @Test void freshInstallHasNoBackupRecord(@TempDir Path dir) {
        var scheduler = new BackupScheduler(null);
        scheduler.init(dir);

        assertEquals(0, scheduler.getLastBackupTime(),
                "A fresh install has no timer state and must not pretend a backup just happened");
    }

    @Test void persistedTimerStateRestoresBackupRecord(@TempDir Path dir) throws IOException {
        var props = new Properties();
        props.setProperty("accruedMs", "60000");
        props.setProperty("lastBackupMs", "1234567890");
        try (var out = Files.newOutputStream(dir.resolve("timer-state.properties"))) {
            props.store(out, "foldbackup timer state");
        }

        var scheduler = new BackupScheduler(null);
        scheduler.init(dir);

        assertEquals(1234567890L, scheduler.getLastBackupTime());
        assertEquals(1, scheduler.getAccumulatedOnlineMinutes());
    }

    @Test void corruptTimerStateFallsBackToNoRecord(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("timer-state.properties"), "lastBackupMs=not-a-number");

        var scheduler = new BackupScheduler(null);
        scheduler.init(dir);

        assertEquals(0, scheduler.getLastBackupTime());
    }

    @Test void deferralNoticeIsEdgeTriggeredUntilReset() {
        var scheduler = new BackupScheduler(() -> 30, () -> 10);
        scheduler.markBackupComplete();
        long base = scheduler.getLastBackupTime();

        assertFalse(scheduler.isMinIntervalElapsed(base + 5 * 60_000L));
        assertTrue(scheduler.isMinIntervalElapsed(base + 10 * 60_000L));

        assertTrue(scheduler.consumeMinIntervalDeferralNotice());
        assertFalse(scheduler.consumeMinIntervalDeferralNotice());

        scheduler.markBackupComplete();
        assertTrue(scheduler.consumeMinIntervalDeferralNotice());
        assertFalse(scheduler.consumeMinIntervalDeferralNotice());

        scheduler.markAttemptFailed();
        assertTrue(scheduler.consumeMinIntervalDeferralNotice());
    }
}
