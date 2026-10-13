package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.StreamIo;
import io.github.truscdo.foldbackup.core.format.ZstdCompressionMemory;
import java.io.IOException;
import java.nio.file.Path;
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

    /**
     * 批处理入口的两种收口：{@code int workers} 是「只关心 worker 数」的便捷形式，在途窗口由
     * {@link BackupResourceBudget#quotaOf} 统一推导；{@link BackupResourceBudget.RegionQuota}
     * 形式原样使用调用点算好的配额。两处不再各自重算窗口。
     */
    static <T> List<T> run(int count, int workers, Task<T> task) throws IOException {
        return run(count, BackupResourceBudget.quotaOf(workers, count), Math.min(workers, 2), task, null, null);
    }

    /**
     * Runs bounded tasks and reports completed indexes from the coordinator
     * thread, preserving ordered, non-concurrent progress callbacks.
     */
    static <T> List<T> run(int count, BackupResourceBudget.RegionQuota quota, int ioConcurrency, Task<T> task,
            IntConsumer completionListener, java.util.function.Consumer<Metrics> listener) throws IOException {
        List<T> results = new ArrayList<>(Collections.nCopies(count, null));
        collect(count, quota, ioConcurrency, task, (index, value) -> {
            results.set(index, value);
            if (completionListener != null) completionListener.accept(index);
        }, listener);
        return results;
    }

    static <T> void collect(int count, int workers, int ioConcurrency, Task<T> task,
            Collector<T> collector, java.util.function.Consumer<Metrics> listener) throws IOException {
        collect(count, BackupResourceBudget.quotaOf(workers, count), ioConcurrency, task, collector, listener);
    }

    /** Coordinator consumes each completed result before admitting further work. */
    static <T> void collect(int count, BackupResourceBudget.RegionQuota quota, int ioConcurrency, Task<T> task,
            Collector<T> collector, java.util.function.Consumer<Metrics> listener) throws IOException {
        if (count < 0) throw new IllegalArgumentException("Invalid task limits");
        if (count == 0) return;
        int workers = quota.workers();
        if (workers < 1) throw new IllegalArgumentException("Invalid task limits");
        int window = Math.min(count, quota.inFlightWindow());
        var failure = new AtomicReference<Throwable>();
        var io = new io.github.truscdo.foldbackup.core.format.IoBudget(ioConcurrency);
        var pool = Executors.newFixedThreadPool(Math.min(count, workers));
        record Completed<T>(int index, T value) {}
        var completed = new ExecutorCompletionService<Completed<T>>(pool);
        Set<Future<Completed<T>>> pending = new HashSet<>();
        int next = 0;
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

    /**
     * 区域任务的内存口径。
     * <p>
     * {@code sourceFrameLevel} 只能从被解码的帧读出，而一次配额服务整批 worker，因此按批取上界。
     * 计划驱动的路径先构造不带源级别的实例，再由 {@link #withSourceFrameLevel} 填入批次上界。
     *
     * @param kind             任务对解码后的字节做什么
     * @param outputLevel      重压输出的 zstd 级别；仅 {@link Kind#COMPRESS} 使用
     * @param sourceFrameLevel 被解码的源帧写入级别（决定解码窗口）
     */
    record TaskWork(Kind kind, int outputLevel, int sourceFrameLevel) {
        enum Kind {
            /** 重新压缩为 LRH（zstd 单线程流式）。 */
            COMPRESS,
            /** 只解压或改写为 MCA/zlib —— 没有 zstd 压缩上下文。 */
            DECOMPRESS,
        }

        /** 只解码，源帧级别已知。 */
        static TaskWork decompress(int sourceFrameLevel) {
            return new TaskWork(Kind.DECOMPRESS, 0, sourceFrameLevel);
        }

        /** 只解码，源帧级别留待 {@link #withSourceFrameLevel} 填入。 */
        static TaskWork decompress() {
            return decompress(0);
        }

        /** 读取 LRH 帧并以 {@code outputLevel} 重压。 */
        static TaskWork recompress(int outputLevel) {
            return new TaskWork(Kind.COMPRESS, outputLevel, 0);
        }

        TaskWork withSourceFrameLevel(int level) {
            return new TaskWork(kind, outputLevel, level);
        }
    }

    /**
     * 只解码的批量任务（世界恢复、快照导出、非合并导入）的配额：预算取离线口径、处理器数取
     * JVM 可见值。计划驱动的路径改走 {@link #regionQuota}，以便注入与 worker 数一致的预算。
     */
    static BackupResourceBudget.RegionQuota decompressQuota(int count, int sourceFrameLevel, int requested) {
        return regionQuota(count, TaskWork.decompress(sourceFrameLevel), requested, 0,
                ArchiveDefaults.exportMemoryBudget(), Runtime.getRuntime().availableProcessors());
    }

    /**
     * 一批 LRH 源文件的帧级别上界；一次配额服务整批，故取 max。
     * <p>
     * 级别写在头部，因此每份只读 {@link LrhFormat#LEVEL_PREFIX_SIZE} 字节，不做解码。
     * <p>
     * 探测是尽力而为的定尺，不是权威校验：读不出头的文件（例如清单指向已删除的 blob）按 zstd
     * 上界 {@link ZstdCompressionMemory#MAX_LEVEL} 计，让实际读取去报告真实错误。调用点据此在
     * 开始工作前就能算出配额，而不必先证明每个源文件都可读。
     */
    static int maxSourceFrameLevel(Iterable<Path> lrhPaths) {
        int level = 0;
        for (Path path : lrhPaths) {
            try {
                level = Math.max(level, LrhFormat.level(path));
            } catch (IOException | RuntimeException e) {
                return ZstdCompressionMemory.MAX_LEVEL;
            }
        }
        return level;
    }

    /**
     * 把 TaskWork 映到每 worker 内存；配额算式集中在 {@link BackupResourceBudget#regionQuota}。
     * 预算与处理器数由调用点注入，使同一次执行只读一次 JVM 状态；只解码的离线条目见
     * {@link #decompressQuota}。
     */
    static BackupResourceBudget.RegionQuota regionQuota(int count, TaskWork taskWork,
            int requested, long reservedMemoryBytes, long budgetBytes, int availableProcessors) {
        long perWorkerBytes = switch (taskWork.kind()) {
            // 合并导入与归档重压：worker 一边解码源 LRH 帧、一边以 outputLevel 重压，两份上下文同时在飞。
            case COMPRESS -> BackupResourceBudget.compressAndDecodeTaskBytes(
                    taskWork.outputLevel(), taskWork.sourceFrameLevel());
            // 世界恢复与校验：只有解码上下文；输出侧 zlib 相对解码窗口可忽略。
            case DECOMPRESS -> BackupResourceBudget.decompressTaskBytes(taskWork.sourceFrameLevel());
        };
        return BackupResourceBudget.regionQuota(count, budgetBytes,
                reservedMemoryBytes, perWorkerBytes, availableProcessors, requested);
    }
    private ArchiveTasks() {}
}
