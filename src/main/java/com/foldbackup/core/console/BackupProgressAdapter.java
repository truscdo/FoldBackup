package com.foldbackup.core.console;

import com.foldbackup.core.FormatUtil;
import com.foldbackup.core.ProgressEvent;
import com.foldbackup.core.ProgressListener;
import com.foldbackup.core.backup.BackupStep;

import java.util.function.Consumer;

/** Terminal presentation of the same backup steps consumed by the Mod. */
public final class BackupProgressAdapter {
    private BackupProgressAdapter() {}

    public static Consumer<BackupStep> toListener(ProgressListener listener) {
        return listener == null ? null : step -> listener.onProgress(toEvent(step));
    }

    public static ProgressEvent toEvent(BackupStep step) {
        return switch (step) {
            case BackupStep.ScanningWorld scan -> {
                String detail = "";
                if (!scan.lastDiscoveredRegion().isEmpty())
                    detail += "last discovered region: " + scan.lastDiscoveredRegion() + "; ";
                if (!scan.lastDiscoveredFile().isEmpty())
                    detail += "last discovered file: " + scan.lastDiscoveredFile() + "; ";
                // The printer keeps the tail of long items; keep failures visible.
                detail += scan.failures() + " failed";
                yield new ProgressEvent("Scanning world", scan.finishedCount(),
                        scan.complete() ? scan.discoveredCount() : -1, detail);
            }
            case BackupStep.PreparingSnapshot preparing -> new ProgressEvent(
                    "Preparing snapshot", 0, -1, preparing.scanStats().format());
            case BackupStep.CommittingSnapshot committing -> new ProgressEvent(
                    "Committing snapshot", 0, -1, committing.snapshotId());
            case BackupStep.Completed completed -> new ProgressEvent("Backup complete", 1, 1,
                    completed.snapshotId() + "; +" + FormatUtil.formatSize(completed.newBytes()) + " new");
            case BackupStep.Failed failed -> new ProgressEvent("Backup failed", 0, -1, failed.errorMessage());
            case BackupStep.CleanupFailed failed -> new ProgressEvent("Snapshot committed; cleanup failed", 0, -1,
                    failed.snapshotId() + "; " + failed.errorMessage());
        };
    }
}
