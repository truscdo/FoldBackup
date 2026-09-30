package com.foldbackup.mod.i18n;

import com.foldbackup.core.backup.BackupStep;
import com.foldbackup.core.backup.ScanStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupStepFormatTest {
    @Test
    void logsDescribePreparationAndSmallByteCountsAccurately() {
        var stats = new ScanStats(1, 0, 2, 1);
        assertTrue(BackupStepFormat.format(new BackupStep.PreparingSnapshot(stats)).startsWith("Preparing snapshot:"));
        assertEquals("Committing snapshot: sample", BackupStepFormat.format(new BackupStep.CommittingSnapshot("sample")));
        assertTrue(BackupStepFormat.format(new BackupStep.Completed(1, "sample", stats, 73)).contains("73 B new"));
        assertTrue(BackupStepFormat.format(new BackupStep.CleanupFailed(1, "sample", "disk error"))
                .contains("sample committed, but cleanup failed"));
    }

    @Test
    void allBundledLanguagesFormatEveryBackupStep() {
        var stats = new ScanStats(1, 0, 2, 1);
        for (String language : List.of("en_us", "zh_cn", "es_es", "pt_br", "ru_ru")) {
            for (BackupStep step : List.of(
                    new BackupStep.ScanningWorld(1, 2, "region/r.0.0.mca", "level.dat", false, 2, 4, 1),
                    new BackupStep.ScanningWorld(1, 2, "", "", true, 2, 2, 1),
                    new BackupStep.PreparingSnapshot(stats), new BackupStep.CommittingSnapshot("sample"),
                    new BackupStep.Completed(1, "sample", stats, 73),
                    new BackupStep.Failed("scan error"), new BackupStep.CleanupFailed(1, "sample", "disk error"))) {
                String text = BackupStepFormat.formatLocalized(step, language);
                assertFalse(text.contains("foldbackup.progress."), language + ": " + text);
                assertFalse(text.contains("%s"), language + ": " + text);
                assertFalse(text.isBlank());
                if (step instanceof BackupStep.Completed) assertTrue(text.contains("73 B"), text);
                if (step instanceof BackupStep.CleanupFailed || step instanceof BackupStep.CommittingSnapshot)
                    assertTrue(text.contains("sample"), text);
            }
        }
        var scan = new BackupStep.ScanningWorld(0, 0, "region/r.0.0.mca", "level.dat", false, 1, 1, 0);
        assertTrue(BackupStepFormat.formatLocalized(scan, "zh_cn").contains("最近发现"));
        assertTrue(BackupStepFormat.formatLocalized(new BackupStep.CleanupFailed(1, "sample", "disk error"), "zh_cn")
                .contains("已提交，但清理失败"));
    }
}
