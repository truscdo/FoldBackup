package com.foldbackup.core;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pluggable logging facade that selects the appropriate backend
 * based on the running platform.
 * <p>
 * <b>Platform detection priority:</b>
 * <ol>
 * <li>Explicit {@link #setPlatform(Platform)} call (for CLI/TUI main)</li>
 * <li>Classpath detection (NeoForge present → {@link Platform#MOD})</li>
 * <li>Fallback to {@link Platform#CLI}</li>
 * </ol>
 * <p>
 * Usage:
 * 
 * <pre>{@code
 * private static final LogUtil LOG = LogUtil.getLogger(MyClass.class);
 * // or
 * private static final LogUtil LOG = LogUtil.getLogger("my-logger");
 *
 * LOG.info("Hello {}", name);
 * LOG.error("Failed to process", exception);
 * }</pre>
 */
public final class LogUtil {

    // ──────────────────────────────────────────────
    // Platform
    // ──────────────────────────────────────────────

    public enum Platform {
        /** NeoForge mod — backed by SLF4J / Log4j2. */
        MOD,
        /** Standalone CLI — backed by {@link System#out} / {@link System#err}. */
        CLI,
        /** Interactive TUI — backed by {@link System#out} / {@link System#err}. */
        TUI
    }

    // ──────────────────────────────────────────────
    // Level
    // ──────────────────────────────────────────────

    public enum Level {
        DEBUG("\u001B[90m", "DBG"),
        INFO("\u001B[36m", "INF"),
        WARN("\u001B[33m", "WRN"),
        ERROR("\u001B[31m", "ERR");

        final String ansiColor;
        final String label;

        Level(String ansiColor, String label) {
            this.ansiColor = ansiColor;
            this.label = label;
        }
    }

    // ──────────────────────────────────────────────
    // Backend interface
    // ──────────────────────────────────────────────

    @FunctionalInterface
    public interface LogBackend {
        /**
         * Log a message at the given level with optional SLF4J-style {@code {}}
         * placeholders.
         */
        void log(Level level, String message, Object... args);

        /**
         * 该后端是否真的会输出 {@link Level#DEBUG} 消息。
         * <p>
         * 供调用方跳过"构造了也会被丢弃"的昂贵诊断输出（遍历目录、
         * 拼接字符串等）。默认 {@code true}：不做级别过滤的后端（CLI/TUI
         * 控制台）给出什么就打印什么；由日志框架做级别过滤的后端必须覆写。
         */
        default boolean isDebugEnabled() {
            return true;
        }

        /**
         * Log a message with a throwable. Default implementation appends throwable
         * after message.
         */
        default void log(Level level, String message, Throwable throwable) {
            log(level, "{}", message);
            throwable.printStackTrace(getStream(level));
        }

        default PrintStream getStream(Level level) {
            return level == Level.ERROR ? System.err : System.out;
        }
    }

    // ──────────────────────────────────────────────
    // Static state
    // ──────────────────────────────────────────────

    private static volatile Platform platform;
    private static final Map<String, LogUtil> instances = new ConcurrentHashMap<>();

    static {
        platform = detectPlatform();
    }

    /**
     * Override the auto-detected platform. Must be called <em>before</em>
     * any {@link #getLogger} call to take effect on already-cached loggers;
     * subsequent {@code getLogger} calls for <em>new</em> names will pick
     * up the override, but existing instances retain their original backend.
     * <p>
     * Safe usage: call at the very top of {@code main()} before any
     * class that uses {@code LogUtil.getLogger()} is loaded.
     */
    public static void setPlatform(Platform p) {
        platform = p;
    }

    /** 返回当前活动的平台。 */
    public static Platform getPlatform() {
        return platform;
    }

    /** 从运行时类路径检测平台。 */
    private static Platform detectPlatform() {
        try {
            Class.forName("net.neoforged.fml.common.Mod");
            return Platform.MOD;
        } catch (ClassNotFoundException e) {
            return Platform.CLI;
        }
    }

    // ──────────────────────────────────────────────
    // Factory
    // ──────────────────────────────────────────────

    /** 获取（或创建）给定名称的日志器。 */
    public static LogUtil getLogger(String name) {
        return instances.computeIfAbsent(name, LogUtil::new);
    }

    /** 获取（或创建）给定类的日志器，使用其简单类名。 */
    public static LogUtil getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    // ──────────────────────────────────────────────
    // Instance
    // ──────────────────────────────────────────────

    private final LogBackend backend;

    private LogUtil(String name) {
        this.backend = createBackend(name);
    }

    private static LogBackend createBackend(String name) {
        return switch (platform) {
            case MOD -> new Slf4jBackend(name);
            case CLI -> new ConsoleBackend(name, false);
            case TUI -> new ConsoleBackend(name, true);
        };
    }

    // ──────────────────────────────────────────────
    // Public instance methods
    // ──────────────────────────────────────────────

    /**
     * 当前后端是否会输出 {@code debug} 消息。
     * <p>
     * 用于门控昂贵的诊断构造；注意 CLI/TUI 后端不做级别过滤，
     * 始终返回 {@code true}，只有 SLF4J 后端反映真实的框架级别。
     */
    public boolean isDebugEnabled() {
        return backend.isDebugEnabled();
    }

    public void info(String message) {
        backend.log(Level.INFO, message);
    }

    public void info(String format, Object... args) {
        backend.log(Level.INFO, format, args);
    }

    public void info(String message, Throwable throwable) {
        backend.log(Level.INFO, message, throwable);
    }

    public void warn(String message) {
        backend.log(Level.WARN, message);
    }

    public void warn(String format, Object... args) {
        backend.log(Level.WARN, format, args);
    }

    public void warn(String message, Throwable throwable) {
        backend.log(Level.WARN, message, throwable);
    }

    public void error(String message) {
        backend.log(Level.ERROR, message);
    }

    public void error(String format, Object... args) {
        backend.log(Level.ERROR, format, args);
    }

    public void error(String message, Throwable throwable) {
        backend.log(Level.ERROR, message, throwable);
    }

    public void debug(String message) {
        backend.log(Level.DEBUG, message);
    }

    public void debug(String format, Object... args) {
        backend.log(Level.DEBUG, format, args);
    }

    public void debug(String message, Throwable throwable) {
        backend.log(Level.DEBUG, message, throwable);
    }

    // ──────────────────────────────────────────────
    // Backend: SLF4J
    // ──────────────────────────────────────────────

    private static final class Slf4jBackend implements LogBackend {

        private final org.slf4j.Logger logger;

        Slf4jBackend(String name) {
            this.logger = org.slf4j.LoggerFactory.getLogger(name);
        }

        @Override
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }

        @Override
        public void log(Level level, String message, Object... args) {
            switch (level) {
                case DEBUG -> logger.debug(message, args);
                case INFO -> logger.info(message, args);
                case WARN -> logger.warn(message, args);
                case ERROR -> logger.error(message, args);
            }
        }

        @Override
        public void log(Level level, String message, Throwable throwable) {
            switch (level) {
                case DEBUG -> logger.debug(message, throwable);
                case INFO -> logger.info(message, throwable);
                case WARN -> logger.warn(message, throwable);
                case ERROR -> logger.error(message, throwable);
            }
        }
    }

    // ──────────────────────────────────────────────
    // Backend: Console (CLI / TUI)
    // ──────────────────────────────────────────────

    private static final class ConsoleBackend implements LogBackend {

        private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

        private static final String RESET = "\u001B[0m";

        private final String name;
        private final boolean tuiMode;

        ConsoleBackend(String name, boolean tuiMode) {
            this.name = name;
            this.tuiMode = tuiMode;
        }

        @Override
        public void log(Level level, String message, Object... args) {
            FormatResult result = args.length > 0
                    ? format(message, args)
                    : new FormatResult(message, null);
            PrintStream out = getStream(level);

            if (tuiMode) {
                out.println(result.text);
                if (result.unconsumedThrowable != null) {
                    result.unconsumedThrowable.printStackTrace(out);
                }
                return;
            }

            // CLI: colored with timestamp and name
            String color = level.ansiColor;
            String label = level.label;
            String time = LocalTime.now().format(TIME_FMT);
            out.printf("%s[%s] [%s] [%s]%s %s%n",
                    color, time, label, name, RESET, result.text);
            if (result.unconsumedThrowable != null) {
                result.unconsumedThrowable.printStackTrace(out);
            }
        }

        @Override
        public void log(Level level, String message, Throwable throwable) {
            log(level, message);
            throwable.printStackTrace(getStream(level));
        }

        /** Result of {@link #format(String, Object...)}. */
        private record FormatResult(String text, Throwable unconsumedThrowable) {
        }

        /**
         * Replace {@code {}} placeholders with the corresponding args.
         * If there are unconsumed args and the last one is a {@link Throwable},
         * it is returned separately so the caller can print the full stack trace.
         */
        private static FormatResult format(String template, Object... args) {
            StringBuilder sb = new StringBuilder(template.length() + args.length * 16);
            int argIdx = 0;
            int cursor = 0;
            while (cursor < template.length() && argIdx < args.length) {
                int idx = template.indexOf("{}", cursor);
                if (idx < 0) {
                    break;
                }
                sb.append(template, cursor, idx);
                Object v = args[argIdx++];
                sb.append(v != null ? v : "null");
                cursor = idx + 2;
            }
            sb.append(template.substring(cursor));

            // If the last unconsumed arg is a Throwable, return it for stack trace
            Throwable throwable = null;
            if (argIdx < args.length && args[args.length - 1] instanceof Throwable t) {
                throwable = t;
            }
            return new FormatResult(sb.toString(), throwable);
        }
    }
}
