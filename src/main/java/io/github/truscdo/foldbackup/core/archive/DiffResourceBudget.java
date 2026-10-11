package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;

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
     * 该级别下 Zstd 自己选择的窗口 log。写入端不指定 windowLog，因此帧窗口就是这个默认值；
     * 帧内并行时每个 worker 会重载一整份窗口作为 prefix，故它也是该缓冲的上界。
     * <p>
     * 取值按实测帧头 Window Descriptor 归纳（1→19、3/6→21、9~15→22、17~19→23、20→25、
     * 21→26、22→27），各档取上界以免低估内存。
     */
    static int zstdDefaultWindowLog(int level) {
        if (level >= 22) return 27;
        if (level >= 20) return 26;
        if (level >= 17) return 23;
        if (level >= 9) return 22;
        if (level >= 3) return 21;
        return 19;
    }

    /**
     * 单个 {@code <dir>/diff.lrhd}（恰好一个 Zstd 帧）的配额。
     * <p>
     * Zstd 是唯一旋钮：实测（2026-10-09）native worker 数是唯一支配项（4 核 1.89→3.36，
     * 24 核大样本 11.04→18.98），而增加比较 worker 从未有收益（4 核持平、24 核 +0.8%）。
     * 因此把整份预算交给 Zstd。
     * <p>
     * 计账不变式由构造保证：{@code zstd × nativeWorker + 2 × chunkBytes ≤ budget}，
     * 因为 {@code zstd} 取 {@code (budget - reserve) / nativeWorker} 的下取整。
     * <p>
     * 帧内实际并行度还受 job 数封顶（{@code jobSize = 1 << max(20, windowLog + 2)}，level 19
     * 为 32 MiB），但 job 数只有在解码时才知道，故此处不据此下调 worker 数。
     *
     * @param requested 期望线程数；{@code <= 0} 表示自动（按可用处理器数）
     * @param level     Zstd 压缩级别
     * @param budget    本次运行可用的内存预算（字节）
     */
    static Limits pipeline(int requested, int level, long budget) {
        int cpus = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(),
                requested <= 0 ? Integer.MAX_VALUE : requested));
        long nativeWorker = BackupResourceBudget.streamingTaskBytes(level) + (1L << zstdDefaultWindowLog(level));
        // 在途 chunk 缓冲恒为 1 份：目录工序同一时刻只持有一个变化区块；×2 仅为余量。
        long chunkBytes = Math.max(1, Math.min(64L << 20, budget / 16));
        long reserve = 2 * chunkBytes;
        int zstd = (int) Math.max(1, Math.min(cpus, (budget - reserve) / nativeWorker));
        return new Limits(zstd, chunkBytes);
    }

    private DiffResourceBudget() {}
}
