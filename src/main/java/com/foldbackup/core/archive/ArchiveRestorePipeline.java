package com.foldbackup.core.archive;

import com.foldbackup.core.format.StreamIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Executes the bounded LRHD reader and Region merge pipeline for a prepared plan. */
final class ArchiveRestorePipeline {
    private record Coordinate(int rx, int rz) {}
    private static final class DeltaWork {
        final LrhdRestoreData delta;
        final List<Integer> indexes = new ArrayList<>();
        final Map<Coordinate, Integer> byCoordinate = new HashMap<>();
        final Set<Coordinate> diffCoordinates = new HashSet<>();
        int expected, completed;
        int ordinal;
        boolean frameVerified, notified;
        DeltaWork(LrhdRestoreData delta, int ordinal) { this.delta = delta; this.ordinal = ordinal; }
    }

    private interface PipelineEvent<T> {}
    private record RegionCompleted<T>(int index, LrhdRestoreData owner, T value, Throwable failure)
            implements PipelineEvent<T> {}
    private record ProducerCompleted<T>(LrhdRestoreData owner, Throwable failure) implements PipelineEvent<T> {}

    @FunctionalInterface private interface SubmitRegion<T> {
        void submit(int index, LrhdRestoreData owner, LrhdRestoreData.StagedRegion staged) throws IOException;
    }

    private final List<ArchiveRestorePlan.Region> regions;
    private final Collection<LrhdRestoreData> deltas;

    ArchiveRestorePipeline(List<ArchiveRestorePlan.Region> regions, Collection<LrhdRestoreData> deltas) {
        this.regions = regions;
        this.deltas = deltas;
    }

    /**
     * Runs selected output Regions while every LRHD frame is still consumed and verified.
     * Results preserve plan indexes; unselected indexes contain {@code null}.
     */
    <T> List<T> run(int level, int threads, java.util.function.Consumer<ArchiveTasks.Metrics> metrics,
            IntPredicate includeRegion, IntConsumer completionListener, ArchiveRestorePlan.RegionTask<T> task)
            throws IOException {
        var selected = new boolean[regions.size()];
        int selectedCount = 0;
        for (int i = 0; i < selected.length; i++) {
            selected[i] = includeRegion.test(i);
            if (selected[i]) selectedCount++;
        }

        var deltaWorks = new ArrayList<DeltaWork>();
        var byDelta = new IdentityHashMap<LrhdRestoreData, DeltaWork>();
        for (var delta : deltas) {
            var work = new DeltaWork(delta, deltaWorks.size());
            for (var indexed : delta.regions)
                work.diffCoordinates.add(new Coordinate(indexed.rx, indexed.rz));
            deltaWorks.add(work);
            byDelta.put(delta, work);
        }
        var baseIndexes = new ArrayList<Integer>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            if (!selected[i]) continue;
            if (region.delta() == null) baseIndexes.add(i);
            else {
                DeltaWork work = byDelta.get(region.delta());
                if (work == null) throw new IOException("Missing LRHD worker for " + region.path());
                var coordinate = new Coordinate(region.rx(), region.rz());
                work.indexes.add(i);
                if (work.diffCoordinates.contains(coordinate)) {
                    if (work.byCoordinate.putIfAbsent(coordinate, i) != null)
                        throw new IOException("Duplicate LRHD region coordinates: " + region.path());
                } else baseIndexes.add(i);
            }
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int requestedTotal = threads <= 0 ? processors : Math.max(1, threads);
        int readerWorkers = deltaWorks.isEmpty() ? 0
                : Math.min(deltaWorks.size(), Math.min(2, Math.min(processors, Math.max(1, requestedTotal - 1))));
        long readerReserve = readerWorkers * 144L * 1024 * 1024;
        int regionLimit = Math.max(1, requestedTotal - readerWorkers);
        int workers = ArchiveTasks.workers(selectedCount, level, regionLimit, readerReserve);
        int window = Math.max(1, Math.min(selectedCount == 0 ? 1 : selectedCount, 2 * workers));
        var permits = new Semaphore(window, true);
        var io = new com.foldbackup.core.format.IoBudget(2);
        var events = new LinkedBlockingQueue<PipelineEvent<T>>();
        var fatal = new AtomicReference<Throwable>();
        var active = new AtomicInteger();
        var peakActive = new AtomicInteger();
        var inFlight = new AtomicInteger();
        var peakInFlight = new AtomicInteger();
        var values = new Object[regions.size()];
        ExecutorService regionPool = Executors.newFixedThreadPool(workers);
        int producerThreads = Math.max(1, Math.min(deltaWorks.size() + (baseIndexes.isEmpty() ? 0 : 1),
                Math.max(1, readerWorkers)));
        ExecutorService producerPool = Executors.newFixedThreadPool(producerThreads);
        boolean success = false;
        long started = System.nanoTime(), completionWait = 0, tailStart = 0;
        var scratch = new ArchiveTemporaryDirectory();
        try (scratch) {
          try {
            SubmitRegion<T> submit = (index, owner, staged) -> {
                var region = regions.get(index);
                try {
                    regionPool.execute(() -> {
                        peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        T value = null;
                        Throwable failure = null;
                        try (var scope = io.enter()) {
                            StreamIo.checkCancelled();
                            Throwable earlier = fatal.get();
                            if (earlier != null) throw new IOException("Archive region pipeline failed", earlier);
                            value = task.run(index, region.source(staged));
                        } catch (Throwable e) {
                            failure = e;
                            fatal.compareAndSet(null, e);
                        } finally {
                            active.decrementAndGet();
                            if (staged != null) {
                                try { staged.close(); }
                                catch (Throwable cleanup) {
                                    if (failure == null) failure = cleanup;
                                    else failure.addSuppressed(cleanup);
                                    fatal.compareAndSet(null, cleanup);
                                }
                            }
                        }
                        events.offer(new RegionCompleted<>(index, owner, value, failure));
                    });
                } catch (RuntimeException rejected) {
                    if (staged != null) {
                        try { staged.close(); } catch (IOException cleanup) { rejected.addSuppressed(cleanup); }
                    }
                    throw new IOException("Could not submit Region task", rejected);
                }
            };

            Runnable baseProducer = () -> {
                Throwable failure = null;
                try (var scope = io.enter()) {
                    for (int index : baseIndexes) {
                        checkPipeline(fatal);
                        reserve(permits, inFlight, peakInFlight);
                        try {
                            submit.submit(index, regions.get(index).delta(), null);
                        } catch (Throwable e) {
                            permits.release(); inFlight.decrementAndGet(); throw e;
                        }
                    }
                } catch (Throwable e) {
                    failure = e; fatal.compareAndSet(null, e);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                events.offer(new ProducerCompleted<>(null, failure));
            };

            for (DeltaWork work : deltaWorks) producerPool.execute(() -> {
                Throwable failure = null;
                try (var scope = io.enter()) {
                    for (int r = 0; r < work.delta.regions.size(); r++) {
                        checkPipeline(fatal);
                        var index = work.delta.regions.get(r);
                        Integer target = work.byCoordinate.get(new Coordinate(index.rx, index.rz));
                        boolean retain = target != null;
                        if (retain) reserve(permits, inFlight, peakInFlight);
                        Path payload = scratch.path.resolve("delta-" + work.ordinal + "-" + r + ".bin");
                        LrhdRestoreData.StagedRegion staged;
                        try {
                            staged = work.delta.stageNext(payload, retain);
                            if (staged == null && retain)
                                throw new IOException("Missing staged LRHD region " + index.rx + "," + index.rz);
                            if (retain) {
                                submit.submit(target, work.delta, staged);
                            }
                        } catch (Throwable e) {
                            if (retain) { permits.release(); inFlight.decrementAndGet(); }
                            throw e;
                        }
                    }
                    work.delta.finish();
                } catch (Throwable e) {
                    failure = e; fatal.compareAndSet(null, e);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                events.offer(new ProducerCompleted<>(work.delta, failure));
            });
            producerPool.execute(baseProducer);

            int producerCount = deltaWorks.size() + 1, finishedProducers = 0, finishedRegions = 0;
            while (finishedProducers < producerCount || finishedRegions < selectedCount) {
                StreamIo.checkCancelled();
                Throwable earlier = fatal.get();
                if (earlier != null) throw asIOException("Archive restore pipeline failed", earlier);
                long waitStart = System.nanoTime();
                PipelineEvent<T> event;
                try { event = events.poll(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Archive restore pipeline interrupted", e);
                }
                completionWait += System.nanoTime() - waitStart;
                if (event == null) continue;
                if (event instanceof RegionCompleted<?> raw) {
                    @SuppressWarnings("unchecked") var completed = (RegionCompleted<T>) raw;
                    permits.release(); inFlight.decrementAndGet(); finishedRegions++;
                    if (completed.failure() != null)
                        throw asIOException("Region task failed: " + regions.get(completed.index()).path(), completed.failure());
                    values[completed.index()] = completed.value();
                    if (completed.owner() == null) {
                        if (completionListener != null) completionListener.accept(completed.index());
                    } else {
                        DeltaWork work = byDelta.get(completed.owner());
                        work.completed++;
                        notifyDeltaIfComplete(work, completionListener);
                    }
                } else if (event instanceof ProducerCompleted<?> raw) {
                    @SuppressWarnings("unchecked") var completed = (ProducerCompleted<T>) raw;
                    finishedProducers++;
                    if (completed.failure() != null)
                        throw asIOException("LRHD reader failed", completed.failure());
                    if (completed.owner() != null) {
                        DeltaWork work = byDelta.get(completed.owner());
                        work.expected = work.indexes.size();
                        work.frameVerified = true;
                        notifyDeltaIfComplete(work, completionListener);
                    }
                    if (finishedProducers == producerCount) tailStart = System.nanoTime();
                }
            }
            success = true;
          } finally {
            stopPool(producerPool, !success);
            stopPool(regionPool, !success);
          }
        }

        if (success && metrics != null) {
            metrics.accept(new ArchiveTasks.Metrics(System.nanoTime() - started, peakActive.get(), peakInFlight.get(),
                    completionWait, io.waitNanos(), tailStart == 0 ? 0 : System.nanoTime() - tailStart));
        }
        List<T> ordered = new ArrayList<>(regions.size());
        for (Object result : values) {
            @SuppressWarnings("unchecked") T value = (T) result;
            ordered.add(value);
        }
        return ordered;
    }

    private static void reserve(Semaphore permits, AtomicInteger inFlight, AtomicInteger peakInFlight)
            throws IOException, InterruptedException {
        permits.acquire();
        int active = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(active, Math::max);
    }

    private static void checkPipeline(AtomicReference<Throwable> fatal) throws IOException {
        StreamIo.checkCancelled();
        Throwable failure = fatal.get();
        if (failure != null) throw asIOException("Archive restore pipeline failed", failure);
    }

    private static IOException asIOException(String message, Throwable cause) {
        return cause instanceof IOException io ? io : new IOException(message, cause);
    }

    private static void notifyDeltaIfComplete(DeltaWork work, IntConsumer listener) {
        if (!work.notified && work.frameVerified && work.completed == work.expected) {
            work.notified = true;
            if (listener != null) for (int index : work.indexes) listener.accept(index);
        }
    }

    private static void stopPool(ExecutorService pool, boolean cancel) {
        if (cancel) pool.shutdownNow(); else pool.shutdown();
        boolean interrupted = false;
        while (!pool.isTerminated()) {
            try { pool.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException e) { interrupted = true; pool.shutdownNow(); }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

}
