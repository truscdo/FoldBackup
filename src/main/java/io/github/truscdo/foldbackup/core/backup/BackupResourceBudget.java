package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.format.ZstdCompressionMemory;
import io.github.truscdo.foldbackup.core.format.ZstdDecompressionMemory;

/**
 * 全仓线程/内存配额的唯一来源。
 *
 * <p>各站点只提供输入（预算、预留、每 worker 内存、处理器数、请求线程数），
 * {@link #regionQuota} 是唯一做除法与 min 链的地方；调用点拿到 {@link RegionQuota} 后原样使用，
 * 不再自行收窄。两类预算口径也集中在此：离线 = {@code ArchiveDefaults.exportMemoryBudget()}，
 * 在线 = {@link #memoryBudgetBytes}。</p>
 */
public final class BackupResourceBudget {

    private static final long MEBIBYTE = 1L << 20;
    private static final long AUTO_MEMORY_PERCENT = 20;
    private static final long AUTO_HEADROOM_PERCENT = 50;
    private static final long MAX_BUFFER_BYTES = 64L << 20;

    /** 官方推导值的余量：×1.3。官方公式不含 bt 族未公开的额外内存、JNI 侧对象与 GC 提交延迟。 */
    private static final long COMPRESS_MARGIN_NUMERATOR = 13;
    private static final long COMPRESS_MARGIN_DENOMINATOR = 10;

    private BackupResourceBudget() {
    }

    /**
     * 一次区域批处理的配额。
     *
     * @param workers        并发 worker 数（由内存、处理器数、工作单元数共同封顶）
     * @param inFlightWindow 在途任务窗口（背压用，= min(count, 2 × workers)）；不是内存量
     */
    public record RegionQuota(int workers, int inFlightWindow) {}

    /**
     * 保守的按级别分档值，含 native 压缩状态；与输入规模无关。
     * <p>
     * 唯一剩余用途是 ZSTDMT（LRHD 差异包，{@code nbWorkers >= 1}）——官方估算 API 对多线程
     * 返回错误，故无公式可用。单线程压缩用 {@link #compressTaskBytes(int)}，解码用
     * {@link #decompressTaskBytes(int)}。
     */
    public static long streamingTaskBytes(int level) {
        return (level >= 20 ? 768L : level >= 17 ? 256L : level >= 10 ? 96L : 32L) * MEBIBYTE;
    }

    /**
     * 单个 zstd 单线程流式压缩任务允许的内存（字节）：官方逐级推导值 ×1.3。
     *
     * <p>与 {@link #streamingTaskBytes(int)} 的分工：本方法是 zstd 压缩上下文自身的内存，只对
     * 「单线程 + 流式 + 未显式设置压缩参数」成立（见 {@link ZstdCompressionMemory}），因此只服务
     * {@code LrhFormat.compress/write} 与 {@code ZstdStream.write} 这些单线程压缩点。
     * ZSTDMT（差分归档，{@code nbWorkers >= 1}）不适用官方公式，继续用
     * {@link #streamingTaskBytes(int)}。</p>
     *
     * @param level zstd 压缩级别；{@code <= 0} 按级别 3 计，&gt;22 按 22 计
     */
    public static long compressTaskBytes(int level) {
        long derived = ZstdCompressionMemory.singleThreadStreamBytes(level);
        return derived * COMPRESS_MARGIN_NUMERATOR / COMPRESS_MARGIN_DENOMINATOR;
    }

    /**
     * 纯解压路径每 worker 的 zstd 解码上下文（字节）：官方流式解压分配值，无余量。
     *
     * @param frameLevel 被解压帧写入时的级别（决定解码窗口），不是输出级别
     */
    public static long decompressTaskBytes(int frameLevel) {
        return ZstdDecompressionMemory.singleThreadStreamBytes(frameLevel);
    }

    /**
     * 「读取 LRH 帧并重压」路径每 worker 的内存（字节）：源解码上下文 + 目标压缩上下文。
     * <p>
     * 这类 worker 在解码源帧的同时持有压缩上下文（合并导入、归档重压），
     * 只算压缩侧会低估。</p>
     *
     * @param compressLevel    目标 LRH 的压缩级别
     * @param sourceFrameLevel 被解码帧写入时的级别
     */
    public static long compressAndDecodeTaskBytes(int compressLevel, int sourceFrameLevel) {
        return Math.addExact(compressTaskBytes(compressLevel), decompressTaskBytes(sourceFrameLevel));
    }

    /**
     * 区域输出的块池字节数。与 {@link #regionQuota} 的 {@code reserveBytes} 同源：
     * 调用点必须把同一个值既当预留、又当实际块池容量，否则会出现「预留了不使用」或「使用了未预留」。
     *
     * @param budgetBytes 本次运行的预算（字节）
     * @param blockBytes  块的粒度（调用方的块池单位）
     */
    public static long regionBufferBytes(long budgetBytes, int blockBytes) {
        long block = Math.max(1, blockBytes);
        return Math.max(2 * block, Math.min(MAX_BUFFER_BYTES, Math.max(0, budgetBytes) / 8));
    }

    /**
     * 区域批处理的配额。自动档（{@code requestedThreads <= 0}）取全部处理器，离线路径使用。
     *
     * @param count             并行工作单元数（超出的部分无意义）
     * @param budgetBytes       本次运行的预算（字节）
     * @param reserveBytes      该流水线自身持有的非压缩内存（块池、reader 预留等）
     * @param perWorkerBytes    每个 worker 的压缩/解码上下文内存
     * @param availableProcessors JVM 可见处理器数
     * @param requestedThreads  期望线程数；{@code <= 0} 表示自动
     */
    public static RegionQuota regionQuota(int count, long budgetBytes, long reserveBytes, long perWorkerBytes,
            int availableProcessors, int requestedThreads) {
        int cpus = Math.max(1, availableProcessors);
        return quota(count, budgetBytes, reserveBytes, perWorkerBytes, cpus, cpus, requestedThreads);
    }

    /**
     * Mod 在线的配额：自动档只取一半处理器，把另一半留给 Minecraft 自身。
     * 显式请求仍以全部处理器为上限（与离线一致）。
     */
    public static RegionQuota modRegionQuota(int count, long budgetBytes, long reserveBytes, long perWorkerBytes,
            int availableProcessors, int requestedThreads) {
        int cpus = Math.max(1, availableProcessors);
        return quota(count, budgetBytes, reserveBytes, perWorkerBytes, cpus, Math.max(1, cpus / 2), requestedThreads);
    }

    /**
     * 在途任务窗口的唯一定义：{@code clamp(2 × workers, 1, max(1, count))}。
     * 不是内存量，只用于背压。
     */
    private static int inFlightWindow(int workers, int count) {
        return (int) Math.clamp(2L * workers, 1, Math.max(1, count));
    }

    /**
     * 由显式 worker 数构造配额，在途窗口与 {@link #regionQuota} 同一算式。
     * <p>
     * 供已自行定好 worker 数、但仍需要同一个窗口的调用点（批处理入口）使用，
     * 使「worker 数」与「窗口」不会在两处各自推导。</p>
     *
     * @param workers 并发 worker 数（至少 1）
     * @param count   并行工作单元数（窗口的上界）
     */
    public static RegionQuota quotaOf(int workers, int count) {
        int selected = Math.max(1, workers);
        return new RegionQuota(selected, inFlightWindow(selected, count));
    }

    /** 全仓唯一的配额算式：{@code workers = max(1, min(count, cpus, limit, usable / perWorker))}。 */
    private static RegionQuota quota(int count, long budgetBytes, long reserveBytes, long perWorkerBytes,
            int cpus, int autoThreads, int requestedThreads) {
        int items = Math.max(0, count);
        int limit = requestedThreads > 0 ? requestedThreads : autoThreads;
        long usable = Math.max(0, budgetBytes - Math.max(0, reserveBytes));
        long memoryWorkers = usable / Math.max(1, perWorkerBytes);
        int workers = Math.clamp(Math.min(Math.min(items, cpus), Math.min(limit, memoryWorkers)),
                1, Integer.MAX_VALUE);
        return new RegionQuota(workers, inFlightWindow(workers, items));
    }

    /**
     * Calculate the total memory budget for in-flight region work.
     * A requested value of zero uses both a fraction of Xmx and a fraction of
     * the currently unused heap, whichever is smaller.
     */
    public static long memoryBudgetBytes(long requestedMebibytes,
            long maxHeapBytes, long usedHeapBytes) {
        long maxHeap = Math.max(1, maxHeapBytes);
        long usedHeap = Math.max(0, Math.min(usedHeapBytes, maxHeap));
        long headroom = maxHeap - usedHeap;

        if (requestedMebibytes > 0) {
            return Math.max(MEBIBYTE, Math.min(requestedMebibytes * MEBIBYTE, headroom));
        }

        long xmxBudget = percent(maxHeap, AUTO_MEMORY_PERCENT);
        long headroomBudget = percent(headroom, AUTO_HEADROOM_PERCENT);
        return Math.max(MEBIBYTE, Math.min(xmxBudget, headroomBudget));
    }

    private static long percent(long value, long percentage) {
        if (value <= 0) {
            return 1;
        }
        if (value > Long.MAX_VALUE / percentage) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, (value * percentage) / 100);
    }
}
