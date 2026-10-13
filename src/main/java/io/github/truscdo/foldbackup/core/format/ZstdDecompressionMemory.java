package io.github.truscdo.foldbackup.core.format;

/**
 * Zstd 单线程流式解压的上下文内存。
 * <p>
 * 依据官方 wiki《Using libzstd in a memory constrained environment》的解压分项：
 *
 * <pre>
 *   历史窗口缓冲   Window_Size
 *   压缩块缓冲     3 × Block_Maximum_Size
 *   合计           Window_Size + 3 × Block_Maximum_Size
 *   Block_Maximum_Size = min(128 KiB, Window_Size)
 * </pre>
 *
 * <p>与压缩侧不同，解压是精确的分配量而非估算，也没有 btopt 族那样未公开的额外开销，
 * 因此不再添加余量。窗口取该级别默认参数表的 {@code windowLog}，即压缩器实际写进帧头的值。</p>
 *
 * <p><b>适用范围</b>：每个解压上下文一份（官方手册「use one different context per thread for
 * parallel execution」），因此并行度等于流数，与压缩侧的 ZSTDMT 不同。单次解压（非流式）的
 * 开销与帧大小无关，另由 {@code ZSTD_sizeof_DCtx()} 查询，不适用本式。</p>
 *
 * <p>解码窗口由帧头决定，{@code ZSTD_d_maxWindowLog} 只设上限（默认 27）；
 * 本项目未设置 {@code ZSTD_d_maxBlockSize}，故块项恒为 128 KiB。</p>
 */
public final class ZstdDecompressionMemory {

    /** {@code ZSTD_BLOCKSIZE_MAX}。 */
    private static final long MAX_BLOCK_SIZE = 1L << 17;

    private ZstdDecompressionMemory() {
    }

    /**
     * 单个流式解压上下文的内存（字节）。
     *
     * @param level 帧写入时使用的压缩级别（决定窗口）；{@code <= 0} 按级别 3 计，&gt;22 按 22 计
     */
    public static long singleThreadStreamBytes(int level) {
        long window = 1L << ZstdCompressionMemory.windowLog(level);
        return window + 3 * Math.min(MAX_BLOCK_SIZE, window);
    }
}
