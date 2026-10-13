package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;
import io.github.truscdo.foldbackup.core.format.ZstdCompressionMemory;

/**
 * Per-frame memory and native Zstd worker allowances.
 * <p>
 * Directories are encoded one at a time: the size of a directory's changed payload is only known
 * while decoding it, so it cannot be used to size a worker pool beforehand. Every encoder therefore
 * asks for the whole allowance, which is what a single large frame needs to saturate the cores.
 */
final class DiffResourceBudget {

    /** One frame's allowance: its native Zstd worker count and the in-flight chunk-buffer limit. */
    record Limits(int zstdWorkers, long chunkBytes) {}

    /**
     * MT 每 worker 的内存上界：单线程上下文再加一份帧内并行重载的窗口。
     * <p>
     * 写入端不指定 windowLog，因此帧窗口就是官方参数表的默认值，窗口项直接取
     * {@link ZstdCompressionMemory#windowLog(int)}：它既是压缩器写进帧头的值，也是帧内
     * 并行时每个 worker 重载为 prefix 的那份缓冲。
     */
    static long mtTaskBytes(int level) {
        return BackupResourceBudget.streamingTaskBytes(level)
                + (1L << ZstdCompressionMemory.windowLog(level));
    }

    /**
     * 单个 {@code <dir>/diff.lrhd}（恰好一个 Zstd 帧）的配额。
     * <p>
     * Zstd 是唯一旋钮：实测（2026-10-09）native worker 数是唯一支配项（4 核 1.89→3.36，
     * 24 核大样本 11.04→18.98），而增加比较 worker 从未有收益（4 核持平、24 核 +0.8%）。
     * 因此把整份预算交给 Zstd。
     * <p>
     * MT 无官方内存公式（官方估算 API 在 {@code nbWorkers >= 1} 时不可用），故每 worker 现金
     * 用 {@link #mtTaskBytes(int)}；配额算式本身不在本类，而在
     * {@link BackupResourceBudget#regionQuota}。
     *
     * @param requested 期望线程数；{@code <= 0} 表示自动（按可用处理器数）
     * @param level     Zstd 压缩级别
     * @param budget    本次运行可用的内存预算（字节）
     */
    static Limits pipeline(int requested, int level, long budget) {
        // 在途 chunk 缓冲恒为 1 份：目录工序同一时刻只持有一个变化区块；×2 仅为余量。
        long chunkBytes = Math.max(1, Math.min(64L << 20, budget / 16));
        var quota = BackupResourceBudget.regionQuota(Integer.MAX_VALUE, budget, 2 * chunkBytes,
                mtTaskBytes(level), Runtime.getRuntime().availableProcessors(), requested);
        return new Limits(quota.workers(), chunkBytes);
    }

    private DiffResourceBudget() {}
}
