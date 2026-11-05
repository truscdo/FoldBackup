package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.restore.RestoreStats;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Formats the check-phase statistics for the CLI and the TUI. */
public final class RestoreStatsPrinter {
    private RestoreStatsPrinter() {
    }

    /**
     * Prints the three statistic groups.
     * <p>
     * {@code deleteExtra} is the already-made decision: {@code TRUE} marks the deletion group as
     * removed, {@code FALSE} as kept. {@code null} means the decision has not been made yet (the
     * TUI prints before asking), so the group stays neutral.
     */
    public static void print(RestoreStats stats, Boolean deleteExtra, boolean dryRun, Path target, PrintStream out) {
        out.println("Target:   " + target);
        out.println("Changes:  " + stats.changedRegions() + " region(s), " + stats.changedFiles() + " file(s)");
        out.println("Skipped:  " + stats.skippedRegions() + " region(s), " + stats.skippedFiles() + " file(s)");
        String deletion = stats.deletedFiles() + " candidate(s)";
        if (Boolean.TRUE.equals(deleteExtra)) deletion += " (remove)";
        else if (Boolean.FALSE.equals(deleteExtra)) deletion += " (kept)";
        out.println("Deletion: " + deletion);
        for (Map.Entry<String, Integer> group : stats.deletedBySuffix().entrySet())
            out.println("  " + group.getKey() + " " + group.getValue());
        if (stats.deletedWithoutSuffix() > 0)
            out.println("  (no extension) " + stats.deletedWithoutSuffix());
        if (!stats.emptiedDirectories().isEmpty())
            out.println("  directories removed entirely: " + String.join(", ", stats.emptiedDirectories()));
        if (dryRun) out.println("Dry run: nothing was written or deleted.");
    }
}
