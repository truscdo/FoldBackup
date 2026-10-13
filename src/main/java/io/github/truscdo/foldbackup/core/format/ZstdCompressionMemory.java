package io.github.truscdo.foldbackup.core.format;

/**
 * Zstd 单线程流式压缩的上下文内存，按官方参数表推导，不是实测值。
 * <p>
 * 依据 zstd v1.5.6 {@code lib/compress/clevels.h} 的
 * {@code ZSTD_defaultCParameters[0]}（注释为 "default - for any srcSize &gt; 256 KB"）
 * 与官方 wiki《Using libzstd in a memory constrained environment》的分项：
 *
 * <pre>
 *   hash 表    4 × 2^hashLog
 *   chain 表   4 × 2^chainLog      仅 strategy != ZSTD_fast 时分配
 *   额外表     +2^hashLog          greedy / lazy / lazy2
 *   流式缓冲   2 × min(128 KiB, 2^windowLog)
 *   历史窗口   2^windowLog
 * </pre>
 *
 * level 19 推导得 88.25 MiB，与本仓库实测的 89.1 MiB 相差 1%。
 * <p>
 * <b>适用范围</b>（四条同时成立才可用；本项目只有 {@code LrhFormat.compress/write} 与
 * {@code ZstdStream.write} 这些单线程流式压缩点满足）：
 * <ul>
 *   <li>{@code nbWorkers == 0} —— 官方估算 API 在 {@code ZSTD_c_nbWorkers >= 1} 时返回错误码，
 *       ZSTDMT（差分归档）的内存由 jobSize 与 buffer 池决定，本类不适用；</li>
 *   <li>流式且未 pledged srcSize —— 单次压缩会把 hashLog/chainLog 截到源大小；</li>
 *   <li>未显式设置 windowLog / chainLog / hashLog / strategy / maxBlockSize；</li>
 *   <li>只统计压缩上下文自身，不含调用方输入输出缓冲与 JNI 侧开销。</li>
 * </ul>
 */
public final class ZstdCompressionMemory {

    /** zstd 支持的最高压缩级别（{@code ZSTD_MAX_CLEVEL}）。 */
    public static final int MAX_LEVEL = 22;

    /** {@code ZSTD_CLEVEL_DEFAULT}：级别 0 的含义。 */
    private static final int DEFAULT_LEVEL = 3;

    /** 镜像 {@code ZSTD_strategy}，只需区分是否分配 chain 表、是否有额外 hash 表。 */
    private static final int FAST = 1, DFAST = 2, GREEDY = 3, LAZY = 4, LAZY2 = 5,
            BTLAZY2 = 6, BTOPT = 7, BTULTRA = 8, BTULTRA2 = 9;

    /** {@code ZSTD_defaultCParameters[0][level].windowLog}，下标 0 未使用。 */
    private static final int[] WINDOW_LOG = {
            0, 19, 20, 21, 21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 22, 23, 23, 23, 25, 26, 27};

    /** {@code ZSTD_defaultCParameters[0][level].chainLog}。 */
    private static final int[] CHAIN_LOG = {
            0, 13, 15, 16, 18, 18, 18, 19, 19, 20, 21, 21, 22, 22, 22, 23, 22, 23, 23, 24, 25, 26, 27};

    /** {@code ZSTD_defaultCParameters[0][level].hashLog}。 */
    private static final int[] HASH_LOG = {
            0, 14, 16, 17, 18, 19, 19, 20, 20, 21, 22, 22, 23, 22, 23, 23, 22, 22, 22, 22, 23, 24, 25};

    /** {@code ZSTD_defaultCParameters[0][level].strat}。 */
    private static final int[] STRATEGY = {
            0, FAST, FAST, DFAST, DFAST, GREEDY, LAZY, LAZY, LAZY2, LAZY2, LAZY2, LAZY2, LAZY2,
            BTLAZY2, BTLAZY2, BTLAZY2, BTOPT, BTOPT, BTULTRA, BTULTRA2, BTULTRA2, BTULTRA2, BTULTRA2};

    /** {@code ZSTD_BLOCKSIZE_MAX}。 */
    private static final long MAX_BLOCK_SIZE = 1L << 17;

    private ZstdCompressionMemory() {
    }

    /**
     * 把任意级别折算到官方表的一行。zstd 把 0 视为默认级别 3、负数视为比 1 更快，
     * 超过 {@link #MAX_LEVEL} 则截到 22；此处统一按「不超过该实际参数」的保守侧取值。
     */
    private static int row(int level) {
        if (level <= 0) return DEFAULT_LEVEL;
        return Math.min(level, MAX_LEVEL);
    }

    /**
     * 该级别下官方参数表的 {@code windowLog}；也是压缩器写进帧头的窗口大小，
     * 因此它同时决定解压端要分配多少历史窗口（见 {@link ZstdDecompressionMemory}）。
     *
     * @param level zstd 压缩级别；{@code <= 0} 按级别 3 计，&gt;22 按 22 计
     */
    public static int windowLog(int level) {
        return WINDOW_LOG[row(level)];
    }

    /**
     * 单线程流式压缩上下文的常驻内存（字节）。
     *
     * @param level zstd 压缩级别；{@code <= 0} 按级别 3 计，&gt;22 按 22 计
     */
    public static long singleThreadStreamBytes(int level) {
        int selected = row(level);
        int strategy = STRATEGY[selected];
        long window = 1L << WINDOW_LOG[selected];
        long chain = strategy == FAST ? 0 : 4L << CHAIN_LOG[selected];
        long hash = 4L << HASH_LOG[selected];
        long buffers = 2 * Math.min(MAX_BLOCK_SIZE, window);
        long extra = strategy == GREEDY || strategy == LAZY || strategy == LAZY2
                ? 1L << HASH_LOG[selected] : 0;
        return window + chain + hash + buffers + extra;
    }
}
