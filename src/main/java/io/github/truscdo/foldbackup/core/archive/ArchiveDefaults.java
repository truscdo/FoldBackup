package io.github.truscdo.foldbackup.core.archive;

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
     * 导出内存预算（字节）。保留 512MB；当 JVM 堆小于 2GB 时改为保留 25%，
     * 避免小堆环境只剩 1 字节导致内存信号量永久不可满足。
     */
    public static long exportMemoryBudget() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long reserve = Math.min(MEMORY_RESERVE, maxMemory / 4);
        return Math.max(1, maxMemory - reserve);
    }

    /** 快照导出与归档恢复共用的默认世界存档目录。 */
    public static Path worldExport(String snapshotId) {
        return Path.of("world-export").resolve(snapshotId);
    }
}
