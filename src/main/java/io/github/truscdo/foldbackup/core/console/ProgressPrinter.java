package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.ProgressListener;

import java.io.PrintStream;
import java.util.function.LongSupplier;

/** Unified single-line renderer for structured CLI/TUI progress events. */
public final class ProgressPrinter implements AutoCloseable {

    private final PrintStream out;
    private final LongSupplier clock;
    private final long started;
    private long last;
    private String phase;
    private int width;
    private boolean awaitingItem;

    public ProgressPrinter(PrintStream out) {
        this(out, System::nanoTime);
    }

    ProgressPrinter(PrintStream out, LongSupplier clock) {
        this.out = out;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    public ProgressListener onProgress() {
        return this::print;
    }

    public synchronized void print(ProgressEvent event) {
        long now = clock.getAsLong();
        if (event.phase().equals(phase) && event.completed() != event.total()
                && !event.item().isEmpty() && now - last < 1_000_000_000L && width > 0) {
            // Always show the first item after a stage heading.
            if (!awaitingItem) {
                return;
            }
        }

        phase = event.phase();
        last = now;
        awaitingItem = event.item().isEmpty();
        String item = event.item().replaceAll("[\\p{Cntrl}]", "?");
        if (item.length() > 80) {
            item = "..." + item.substring(item.length() - 77);
        }
        String count = event.total() < 0
                ? event.completed() + "/?"
                : event.completed() + "/" + event.total();
        String percent = event.total() < 0
                ? " --%"
                : String.format("%3d%%", event.total() == 0
                        ? 0 : Math.min(100, event.completed() * 100 / event.total()));
        String line = String.format("  [%s] %s %s | %ds | %s", percent, phase, count,
                Math.max(0, (now - started) / 1_000_000_000L), item);
        out.print("\r" + line + " ".repeat(Math.max(0, width - line.length())));
        out.flush();
        width = line.length();
    }

    @Override
    public synchronized void close() {
        if (width > 0) {
            out.println();
            out.flush();
            width = 0;
        }
    }
}
