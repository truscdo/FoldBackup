package io.github.truscdo.foldbackup.mod.i18n;

import io.github.truscdo.foldbackup.core.backup.BackupStep;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.backup.ScanStats;
import io.github.truscdo.foldbackup.core.task.StepProgress;

/** Formats backup progress for server logs and localized in-game messages. */
public final class BackupStepFormat {
    private BackupStepFormat() {
    }

    /** Stable English text for server logs. */
    public static String format(StepProgress sp) {
        if (sp == null) {
            return "";
        }
        if (!(sp instanceof BackupStep step)) return sp.phase();
        return switch (step) {
            case BackupStep.ScanningWorld sw -> formatScanningLog(sw);
            case BackupStep.PreparingSnapshot preparing -> "Preparing snapshot: " + preparing.scanStats().format();
            case BackupStep.CommittingSnapshot committing -> "Committing snapshot: " + committing.snapshotId();
            case BackupStep.Completed completed -> "Backup complete: " + completed.snapshotId()
                    + " (" + completed.scanStats().format() + ", +" + FormatUtil.formatSize(completed.newBytes()) + " new)";
            case BackupStep.Failed failed -> failed.errorMessage();
            case BackupStep.CleanupFailed failed -> "Snapshot " + failed.snapshotId()
                    + " committed, but cleanup failed: " + failed.errorMessage();
        };
    }

    /** Localized text for chat/status output; the language is selected per player by the caller. */
    public static String formatLocalized(StepProgress sp, String language) {
        if (sp == null) {
            return "";
        }
        if (!(sp instanceof BackupStep step))
            return FoldBackupTranslations.textForLanguage(language, "foldbackup.progress.unknown");
        return switch (step) {
            case BackupStep.ScanningWorld sw -> formatScanning(sw, language);
            case BackupStep.PreparingSnapshot preparing -> formatPreparing(preparing.scanStats(), language);
            case BackupStep.CommittingSnapshot committing -> FoldBackupTranslations.textForLanguage(
                    language, "foldbackup.progress.committing", committing.snapshotId());
            case BackupStep.Completed completed -> formatCompleted(completed, language);
            case BackupStep.Failed ignored -> FoldBackupTranslations.textForLanguage(
                    language, "foldbackup.progress.failed");
            case BackupStep.CleanupFailed failed -> FoldBackupTranslations.textForLanguage(
                    language, "foldbackup.progress.cleanup_failed", failed.snapshotId());
        };
    }

    private static String formatScanningLog(BackupStep.ScanningWorld sw) {
        if (sw.complete()) {
            return "Scan complete — " + sw.regionsProcessed() + " regions, "
                    + sw.filesProcessed() + " files, " + sw.failures() + " failed";
        }
        return "Processing: " + sw.regionsProcessed() + "/" + sw.regionsDiscovered() + " regions"
                + (sw.lastDiscoveredRegion().isEmpty() ? "" : " (last discovered: " + sw.lastDiscoveredRegion() + ")")
                + ", " + sw.filesProcessed() + "/" + sw.filesDiscovered() + " files, " + sw.failures() + " failed"
                + (sw.lastDiscoveredFile().isEmpty() ? "" : " (last discovered: " + sw.lastDiscoveredFile() + ")");
    }

    private static String formatScanning(BackupStep.ScanningWorld sw, String language) {
        if (sw.complete()) {
            return FoldBackupTranslations.textForLanguage(language, "foldbackup.progress.scan_complete",
                    sw.regionsProcessed(), sw.filesProcessed(), sw.failures());
        }
        String region = lastDiscovered(sw.lastDiscoveredRegion(), language);
        String file = lastDiscovered(sw.lastDiscoveredFile(), language);
        return FoldBackupTranslations.textForLanguage(language, "foldbackup.progress.scanning",
                sw.regionsProcessed(), sw.regionsDiscovered(), region,
                sw.filesProcessed(), sw.filesDiscovered(), sw.failures(), file);
    }

    private static String lastDiscovered(String path, String language) {
        return path.isEmpty() ? "" : FoldBackupTranslations.textForLanguage(
                language, "foldbackup.progress.last_discovered", path);
    }

    private static String formatPreparing(ScanStats stats, String language) {
        return FoldBackupTranslations.textForLanguage(language, "foldbackup.progress.preparing",
                stats.regionCount(), stats.changedRegions(), stats.fileCount(), stats.changedFiles());
    }

    private static String formatCompleted(BackupStep.Completed completed, String language) {
        ScanStats stats = completed.scanStats();
        return FoldBackupTranslations.textForLanguage(language, "foldbackup.progress.completed",
                completed.snapshotId(), stats.regionCount(), stats.changedRegions(),
                stats.fileCount(), stats.changedFiles(), FormatUtil.formatSize(completed.newBytes()));
    }
}
