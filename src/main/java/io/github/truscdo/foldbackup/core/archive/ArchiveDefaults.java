package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;

import java.nio.file.Path;

/**
 * 归档的级别常量与默认路径唯一来源。
 * <p>
 * {@code cli/ArchiveCli} 与 {@code tui/ArchiveTui} 的压缩级别、默认目录一律引用
 * 本类，避免多处裸常量漂移。
 */
public final class ArchiveDefaults {

    /** 归档创建压缩级别（CLI archive create 默认，TUI 创建归档共用）。 */
    public static final int ARCHIVE_LEVEL = 19;

    /** 全量导出重压缩级别（与 {@link #ARCHIVE_LEVEL} 一致，语义独立）。 */
    public static final int RECOMPRESS_LEVEL = 19;

    /** 恢复压缩级别（CLI archive export 与 TUI 恢复一致）。 */
    public static final int RESTORE_LEVEL = 6;

    /** 快照导出（在线快照 → 区域文件）压缩级别。 */
    public static final int EXPORT_LEVEL = 6;

    /** 内存预算中留给堆内其他用途的保留量（512 MB）。 */
    private static final long MEMORY_RESERVE = 512L * 1024 * 1024;

    private ArchiveDefaults() {
    }

    /**
     * 计算全量导出默认并行线程数：
     * {@code clamp(min(availableProcessors, memoryThreads, workItems), 1, ...)}，
     * 其中 {@code memoryThreads} 是内存可支撑的并发任务数（见 {@link #memoryConcurrency}）。
     * <p>
     * 归档创建当前只作为 CLI/TUI 离线任务运行，因此默认使用 JVM 可用处理器数；
     * Mod 在线限流策略待归档创建接入 Mod 后单独设计。
     *
     * @param workItems     本次导出的并行工作单元数（去重后的 region 对象数）
     * @param avgRegionSize 平均 region 解压后大小（字节）；&lt;= 0 时只按级别估算每任务字节
     * @return 至少 1 的线程数
     */
    public static int defaultExportThreads(int workItems, long avgRegionSize) {
        long memoryThreads = memoryConcurrency(avgRegionSize);
        long min = Math.min(Runtime.getRuntime().availableProcessors(),
                Math.min(memoryThreads, Math.max(1, workItems)));
        return (int) Math.max(1, min);
    }

    /**
     * 导出内存预算（字节）。保留 512MB；当 JVM 堆小于 2GB 时改为保留 25%，
     * 避免小堆环境只剩 1 字节导致内存信号量永久不可满足。
     */
    public static long exportMemoryBudget() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long reserve = Math.min(MEMORY_RESERVE, maxMemory / 4);
        return Math.max(1, maxMemory - reserve);
    }

    /** 每个任务在 region 数据之外额外持有的工作副本倍数。 */
    private static final long MEMORY_TASK_COPY_FACTOR = 4;

    /**
     * 内存可支撑的并发任务数（不是字节数）：
     * {@code max(1, exportMemoryBudget() / (native + factor × avgRegionSize))}。
     * <p>
     * {@code native} 是级别相关的压缩器状态，{@code factor × avgRegionSize} 覆盖任务持有的
     * region 解码数据与工作副本，使并发能力随实际规模变化而不只看级别。
     */
    static long memoryConcurrency(long avgRegionSize) {
        long nativeBytes = BackupResourceBudget.streamingTaskBytes(RECOMPRESS_LEVEL);
        long copyBytes = MEMORY_TASK_COPY_FACTOR * Math.min(Math.max(0, avgRegionSize),
                Long.MAX_VALUE / MEMORY_TASK_COPY_FACTOR);
        return Math.max(1, exportMemoryBudget() / Math.max(1, nativeBytes + copyBytes));
    }

    /** 快照导出与归档恢复共用的默认世界存档目录。 */
    public static Path worldExport(String snapshotId) {
        return Path.of("world-export").resolve(snapshotId);
    }
}
