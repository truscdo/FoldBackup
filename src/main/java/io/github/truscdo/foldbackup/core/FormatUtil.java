package io.github.truscdo.foldbackup.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * CLI、TUI 与 Mod 命令输出的共享格式化工具。
 */
public final class FormatUtil {

    private static final DateTimeFormatter EPOCH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private FormatUtil() {
    }

    /** 格式化 epoch 秒为本地时区 {@code yyyy-MM-dd HH:mm:ss}。 */
    public static String formatEpoch(long epochSeconds) {
        return formatEpoch(epochSeconds, ZoneId.systemDefault());
    }

    static String formatEpoch(long epochSeconds, ZoneId zone) {
        return EPOCH_FORMATTER.format(Instant.ofEpochSecond(epochSeconds).atZone(zone));
    }

    /**
     * 将字节计数格式化为人类可读的 B/K/M/G 字符串（无空格）。
     *
     * <pre>
     *   formatSize(0)          → "0B"
     *   formatSize(1023)       → "1023B"
     *   formatSize(1024)       → "1.0K"
     *   formatSize(1048576)    → "1.0M"
     *   formatSize(1073741824) → "1.0G"
     * </pre>
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1fG", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
