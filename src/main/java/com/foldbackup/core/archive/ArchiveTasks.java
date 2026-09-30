package com.foldbackup.core.archive;

import com.foldbackup.core.format.StreamIo;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Bounded completion-driven jobs; only the coordinator publishes ordered results. */
final class ArchiveTasks {
    @FunctionalInterface interface Task<T> { T run(int index) throws IOException; }
    @FunctionalInterface interface Collector<T> { void accept(int index, T value) throws IOException; }
    record Metrics(long elapsedNanos, int peakActive, int peakInFlight, long completionWaitNanos,
            long ioWaitNanos, long tailNanos) {}

    static <T> List<T> run(int count, int workers, Task<T> task) throws IOException {
        return run(count, workers, Math.min(workers, 2), task);
    }

    static <T> List<T> run(int count, int workers, int ioConcurrency, Task<T> task) throws IOException {
        return run(count, workers, ioConcurrency, task, null);
    }

    static <T> List<T> run(int count, int workers, int ioConcurrency, Task<T> task,
            java.util.function.Consumer<Metrics> listener) throws IOException {
        return run(count, workers, ioConcurrency, task, null, listener);
    }

    /**
     * Runs bounded tasks and reports completed indexes from the coordinator
     * thread, preserving ordered, non-concurrent progress callbacks.
     */
    static <T> List<T> run(int count, int workers, int ioConcurrency, Task<T> task,
            IntConsumer completionListener, java.util.function.Consumer<Metrics> listener) throws IOException {
        List<T> results = new ArrayList<>(Collections.nCopies(count, null));
        collect(count, workers, ioConcurrency, task, (index, value) -> {
            results.set(index, value);
            if (completionListener != null) completionListener.accept(index);
        }, listener);
        return results;
    }

    /** Coordinator consumes each completed result before admitting further work. */
    static <T> void collect(int count, int workers, int ioConcurrency, Task<T> task,
            Collector<T> collector, java.util.function.Consumer<Metrics> listener) throws IOException {
        if (count < 0 || workers < 1) throw new IllegalArgumentException("Invalid task limits");
        if (count == 0) return;
        var failure = new AtomicReference<Throwable>();
        var io = new com.foldbackup.core.format.IoBudget(ioConcurrency);
        var pool = Executors.newFixedThreadPool(Math.min(count, workers));
        record Completed<T>(int index, T value) {}
        var completed = new ExecutorCompletionService<Completed<T>>(pool);
        Set<Future<Completed<T>>> pending = new HashSet<>();
        int next = 0;
        int window = (int) Math.min(count, 2L * workers);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peakActive = new java.util.concurrent.atomic.AtomicInteger();
        int peakInFlight = 0;
        long started = System.nanoTime(), waitNanos = 0, tailStart = 0;
        boolean success = false;
        try {
            for (int done = 0; done < count; done++) {
                while (next < count && pending.size() < window && failure.get() == null) {
                    int index = next++;
                    pending.add(completed.submit(() -> {
                        peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try (var scope = io.enter()) {
                            StreamIo.checkCancelled();
                            if (failure.get() != null) throw new IOException("Archive task group failed", failure.get());
                            return new Completed<>(index, task.run(index));
                        } catch (Throwable e) { failure.compareAndSet(null, e); throw e; }
                        finally { active.decrementAndGet(); }
                    }));
                    peakInFlight = Math.max(peakInFlight, pending.size());
                }
                StreamIo.checkCancelled();
                if (failure.get() != null) throw new IOException("Archive task failed", failure.get());
                long waitStart = System.nanoTime();
                Future<Completed<T>> future = completed.take();
                waitNanos += System.nanoTime() - waitStart;
                pending.remove(future);
                Completed<T> result = future.get();
                try (var scope = io.enter()) { collector.accept(result.index(), result.value()); }
                if (next == count && pending.size() < workers && tailStart == 0) tailStart = System.nanoTime();
            }
            success = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Archive tasks interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Archive task failed", failure.get() == null ? e.getCause() : failure.get());
        } finally {
            for (var future : pending) future.cancel(true);
            pool.shutdownNow();
            pool.close(); // Join before callers delete input spools or temporary regions.
            if (success && listener != null) listener.accept(new Metrics(System.nanoTime() - started, peakActive.get(),
                    peakInFlight, waitNanos, io.waitNanos(), tailStart == 0 ? 0 : System.nanoTime() - tailStart));
        }
    }

    static int workers(int count, int level) {
        return workers(count, level, 0);
    }
    static int workers(int count, int level, int requested) {
        return workers(count, level, requested, 0);
    }
    static int workers(int count, int level, int requested, long reservedMemoryBytes) {
        long availableMemory = Math.max(0, ArchiveDefaults.exportMemoryBudget() - Math.max(0, reservedMemoryBytes));
        int memoryWorkers = (int) Math.max(1, Math.min(Integer.MAX_VALUE, availableMemory /
                com.foldbackup.core.backup.BackupResourceBudget.streamingTaskBytes(level)));
        return (int) Math.max(1, Math.min(Math.min(count, Runtime.getRuntime().availableProcessors()),
                Math.min(requested <= 0 ? Integer.MAX_VALUE : requested, memoryWorkers)));
    }
    private ArchiveTasks() {}
}
