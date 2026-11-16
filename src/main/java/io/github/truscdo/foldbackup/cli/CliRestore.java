package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.console.ProgressPrinter;
import io.github.truscdo.foldbackup.core.console.RestoreStatsPrinter;
import io.github.truscdo.foldbackup.core.restore.RestoreOperation;
import io.github.truscdo.foldbackup.core.restore.RestoreSource;

import java.io.IOException;
import java.nio.file.Path;

/** CLI 侧快照导出与归档恢复共用的进度、执行与统计打印编排。 */
final class CliRestore {

    @FunctionalInterface
    interface SourceOpener {
        RestoreSource open() throws IOException;
    }

    private CliRestore() {}

    static void run(SourceOpener opener, String sourceName, Path output, boolean dryRun,
            boolean deleteExtra, int threads, int compressionLevel) throws IOException {
        RestoreOperation.Outcome outcome;
        try (var source = opener.open();
                var progress = new ProgressPrinter(System.err)) {
            System.out.println("Exporting " + sourceName + " to " + output + " ...");
            outcome = RestoreOperation.run(source, output,
                    new RestoreOperation.Options(dryRun, deleteExtra, threads, compressionLevel),
                    progress.onProgress());
        }
        RestoreStatsPrinter.print(outcome.stats(), deleteExtra, dryRun, outcome.target(), System.out);
        System.out.println("Done: " + outcome.target());
    }
}
