package com.foldbackup.core;

/**
 * CLI 和 TUI 输出的共享格式化工具。
 */
public final class FormatUtil {

    private FormatUtil() {
    }

    /**
     * 将字节计数格式化为人类可读的字符串。
     * 
     * <pre>
     *   formatSize(0)        → "0 B"
     *   formatSize(1023)     → "1023 B"
     *   formatSize(1024)     → "1.0K"
     *   formatSize(1048576)  → "1.0M"
     * </pre>
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024.0));
    }
}
