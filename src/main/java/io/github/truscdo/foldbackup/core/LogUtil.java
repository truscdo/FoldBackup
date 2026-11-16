package io.github.truscdo.foldbackup.core;

import io.github.truscdo.foldbackup.core.logging.ConsoleLogBackend;
import io.github.truscdo.foldbackup.core.logging.Slf4jLogBackend;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中式日志门面：根据当前运行平台动态路由后端，并统一控制台日志级别与输出协调。
 * <p>
 * <b>平台检测优先级：</b>
 * <ol>
 * <li>显式调用 {@link #setPlatform(Platform)}（用于 CLI/TUI 入口）</li>
 * <li>类路径检测（存在 NeoForge → {@link Platform#MOD}）</li>
 * <li>兜底回退到 {@link Platform#CLI}</li>
 * </ol>
 * <p>
 * 与早期实现不同，即使在 {@link #setPlatform(Platform)} 之前已通过 {@link #getLogger}
 * 缓存了静态实例，后续调用仍会自动切换至最新平台对应的后端，不再滞留旧模式。
 */
public final class LogUtil {

    // ──────────────────────────────────────────────
    // Platform
    // ──────────────────────────────────────────────

    public enum Platform {
        /** NeoForge mod — 委托给 SLF4J / Log4j2。 */
        MOD,
        /** 独立 CLI — 受级别过滤与终端协调器保护的控制台输出。 */
        CLI,
        /** 交互式 TUI — 受级别过滤与终端协调器保护的简洁控制台输出。 */
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

        private final String ansiColor;
        private final String label;

        Level(String ansiColor, String label) {
            this.ansiColor = ansiColor;
            this.label = label;
        }

        public String ansiColor() {
            return ansiColor;
        }

        public String label() {
            return label;
        }

        /** 当前级别是否达到或高于给定阈值。 */
        public boolean atLeast(Level threshold) {
            return threshold == null || this.ordinal() >= threshold.ordinal();
        }
    }

    // ──────────────────────────────────────────────
    // Backend interface
    // ──────────────────────────────────────────────

    @FunctionalInterface
    public interface LogBackend {
        /** 按给定级别与可选的 SLF4J 风格 {@code {}} 占位符记录日志。 */
        void log(Level level, String message, Object... args);

        /**
         * 当前后端是否实际输出 {@link Level#DEBUG} 日志，供调用方跳过昂贵的诊断构造。
         */
        default boolean isDebugEnabled() {
            return true;
        }

        /** 记录带异常堆栈的日志。 */
        default void log(Level level, String message, Throwable throwable) {
            log(level, "{}", message);
            throwable.printStackTrace(getStream(level));
        }

        default PrintStream getStream(Level level) {
            return level == Level.WARN || level == Level.ERROR ? System.err : System.out;
        }
    }

    // ──────────────────────────────────────────────
    // Static state
    // ──────────────────────────────────────────────

    private static volatile Platform platform = detectPlatform();
    private static volatile Level consoleLevel = detectConsoleLevel();
    private static final Map<String, LogUtil> instances = new ConcurrentHashMap<>();

    /**
     * 设置当前运行平台；所有已缓存和新创建的 {@link LogUtil} 均立即感知该切换。
     */
    public static void setPlatform(Platform p) {
        platform = Objects.requireNonNull(p, "platform");
    }

    /** 返回当前活动的平台。 */
    public static Platform getPlatform() {
        return platform;
    }

    /** 设置 CLI/TUI 控制台后端的最低输出级别（默认为 {@link Level#WARN}）。 */
    public static void setConsoleLevel(Level level) {
        consoleLevel = Objects.requireNonNull(level, "level");
    }

    /** 返回 CLI/TUI 控制台后端当前的最低输出级别。 */
    public static Level getConsoleLevel() {
        return consoleLevel;
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

    /** 从系统属性 {@code foldbackup.logLevel} 读取控制台日志阈值，默认 {@link Level#WARN}。 */
    private static Level detectConsoleLevel() {
        String configured = System.getProperty("foldbackup.logLevel");
        if (configured == null || configured.isBlank()) {
            return Level.WARN;
        }
        try {
            return Level.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Level.WARN;
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
    // Instance (dynamic platform resolution)
    // ──────────────────────────────────────────────

    private final String name;
    private volatile Platform cachedPlatform;
    private volatile LogBackend cachedBackend;

    private LogUtil(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    private LogBackend backend() {
        Platform current = platform;
        LogBackend backend = cachedBackend;
        if (backend != null && cachedPlatform == current) {
            return backend;
        }
        synchronized (this) {
            current = platform;
            if (cachedBackend == null || cachedPlatform != current) {
                cachedBackend = createBackend(name, current);
                cachedPlatform = current;
            }
            return cachedBackend;
        }
    }

    private static LogBackend createBackend(String name, Platform activePlatform) {
        return switch (activePlatform) {
            case MOD -> new Slf4jLogBackend(name);
            case CLI -> new ConsoleLogBackend(name, false, LogUtil::getConsoleLevel);
            case TUI -> new ConsoleLogBackend(name, true, LogUtil::getConsoleLevel);
        };
    }

    // ──────────────────────────────────────────────
    // Public instance methods
    // ──────────────────────────────────────────────

    /** 当前活动后端是否会输出 {@code debug} 消息。 */
    public boolean isDebugEnabled() {
        return backend().isDebugEnabled();
    }

    public void info(String message) {
        backend().log(Level.INFO, message);
    }

    public void info(String format, Object... args) {
        backend().log(Level.INFO, format, args);
    }

    public void info(String message, Throwable throwable) {
        backend().log(Level.INFO, message, throwable);
    }

    public void warn(String message) {
        backend().log(Level.WARN, message);
    }

    public void warn(String format, Object... args) {
        backend().log(Level.WARN, format, args);
    }

    public void warn(String message, Throwable throwable) {
        backend().log(Level.WARN, message, throwable);
    }

    public void error(String message) {
        backend().log(Level.ERROR, message);
    }

    public void error(String format, Object... args) {
        backend().log(Level.ERROR, format, args);
    }

    public void error(String message, Throwable throwable) {
        backend().log(Level.ERROR, message, throwable);
    }

    public void debug(String message) {
        backend().log(Level.DEBUG, message);
    }

    public void debug(String format, Object... args) {
        backend().log(Level.DEBUG, format, args);
    }

    public void debug(String message, Throwable throwable) {
        backend().log(Level.DEBUG, message, throwable);
    }
}
