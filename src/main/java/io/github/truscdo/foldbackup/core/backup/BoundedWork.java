package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.Rethrow;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Ordered collection with a bounded submission window; only the caller collects results. */
final class BoundedWork<T> implements AutoCloseable {
    @FunctionalInterface interface Collector<T> { void accept(T value) throws IOException; }
    private final ExecutorService executor;
    private final ArrayDeque<Future<T>> pending = new ArrayDeque<>();
    private final int capacity;
    private final Collector<T> collector;
    BoundedWork(int workers, Collector<T> collector) {
        this(BackupResourceBudget.quotaOf(workers, Integer.MAX_VALUE), collector);
    }

    BoundedWork(BackupResourceBudget.RegionQuota quota, Collector<T> collector) {
        executor = Executors.newFixedThreadPool(quota.workers());
        capacity = quota.inFlightWindow();
        this.collector = collector;
    }
    void submit(Callable<T> task) throws IOException {
        if (pending.size() >= capacity) collect();
        pending.addLast(executor.submit(task));
    }
    void finish() throws IOException { while (!pending.isEmpty()) collect(); }
    private void collect() throws IOException {
        collector.accept(Rethrow.awaitIo(pending.getFirst(), "Scan interrupted", "Scan failed"));
        pending.removeFirst();
    }
    public void close() {
        for (var future : pending) future.cancel(true);
        executor.shutdownNow();
        // Repository lock must outlive all writers.
        boolean interrupted = Thread.interrupted();
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
