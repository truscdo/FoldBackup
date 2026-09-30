package com.foldbackup.core;

/**
 * Result of a backup attempt returned by
 * {@link com.foldbackup.mod.backup.BackupExecutor}.
 * <p>
 * Each value represents a distinct terminal outcome — the caller can
 * map each to an appropriate user-facing message without parsing
 * booleans or exceptions.
 */
public enum BackupResult {

    /** 备份成功完成。 */
    SUCCESS,

    /** 最小墙钟间隔尚未过去；使用 --force 强制跳过。 */
    TOO_SOON,

    /** 另一个备份已在进行中。 */
    ALREADY_RUNNING,

    /** 后端尚未初始化（服务器仍在启动）。 */
    NOT_INITIALIZED,

    /** 备份周期抛出异常；查看日志了解详情。 */
    FAILED
}
