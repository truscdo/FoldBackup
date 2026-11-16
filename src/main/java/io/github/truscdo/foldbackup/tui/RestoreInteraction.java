package io.github.truscdo.foldbackup.tui;

import io.github.truscdo.foldbackup.core.console.ProgressPrinter;
import io.github.truscdo.foldbackup.core.console.RestoreStatsPrinter;
import io.github.truscdo.foldbackup.core.restore.RestoreOperation;
import io.github.truscdo.foldbackup.core.restore.RestoreSource;

import java.io.IOException;
import java.nio.file.Path;

/** TUI 目录导出与归档恢复共用的内层交互流程（检查 → 统计 → 策略 → 线程数 → 执行）。 */
final class RestoreInteraction {

    enum Flow { DONE, BACK_TO_PATH, CANCEL }

    @FunctionalInterface
    interface SourceOpener {
        RestoreSource open() throws IOException;
    }

    private RestoreInteraction() {}

    static Flow run(ConsolePrompt prompt, SourceOpener opener, Path outputPath,
            int compressionLevel, String activeMessage, String failurePrefix) {
        try (var source = opener.open()) {
            RestoreOperation.Session session;
            try (var progress = new ProgressPrinter(System.out)) {
                session = RestoreOperation.prepare(source, outputPath, 0, progress.onProgress());
            } catch (IOException e) {
                System.err.println(failurePrefix + e.getMessage() + "\n");
                return Flow.CANCEL;
            }
            try (session) {
                System.out.println();
                RestoreStatsPrinter.print(session.stats(), null, false, session.target(), System.out);
                System.out.println();
                while (true) {
                    int choice = prompt.readInt(
                            "1 = overwrite, 2 = delete extra, 3 = cancel to main menu, 0 = back: ", 0, 3);
                    if (choice == 3) return Flow.CANCEL;
                    // Blank input and EOF follow the existing TUI convention: any non-positive
                    // choice goes back one level.
                    if (choice <= 0) return Flow.BACK_TO_PATH;

                    boolean deleteExtra = choice == 2;
                    while (true) {
                        int threads = prompt.readThreadCount();
                        if (threads == ConsolePrompt.EOF) return Flow.CANCEL;
                        if (threads == ConsolePrompt.BACK) break;

                        System.out.println(activeMessage);
                        try (var progress = new ProgressPrinter(System.out)) {
                            var outcome = session.execute(deleteExtra, threads, compressionLevel,
                                    progress.onProgress());
                            System.out.println("Done: " + outcome.target().toAbsolutePath().normalize()
                                    + ", deleted extra: " + outcome.deleted() + "\n");
                        } catch (IOException e) {
                            System.err.println(failurePrefix + e.getMessage() + "\n");
                        }
                        return Flow.DONE;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println(failurePrefix + e.getMessage() + "\n");
            return Flow.CANCEL;
        }
    }
}
