package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.LogUtil;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * CLI 与 TUI 共用的控制台日志后端。
 * <p>
 * 支持按当前配置级别过滤（默认 {@link LogUtil.Level#WARN}，避免诊断信息在 CLI/TUI 刷屏），
 * 并通过 {@link ConsoleOutputCoordinator} 与正在刷新的进度条保持互斥，防止输出混行。
 */
public final class ConsoleLogBackend implements LogUtil.LogBackend {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String RESET = "\u001B[0m";

    private final String name;
    private final boolean tuiMode;
    private final Supplier<LogUtil.Level> levelThreshold;
    private final ConsoleOutputCoordinator coordinator;

    public ConsoleLogBackend(String name, boolean tuiMode, Supplier<LogUtil.Level> levelThreshold) {
        this(name, tuiMode, levelThreshold, ConsoleOutputCoordinator.system());
    }

    public ConsoleLogBackend(String name, boolean tuiMode,
            Supplier<LogUtil.Level> levelThreshold, ConsoleOutputCoordinator coordinator) {
        this.name = Objects.requireNonNull(name, "name");
        this.tuiMode = tuiMode;
        this.levelThreshold = Objects.requireNonNull(levelThreshold, "levelThreshold");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public boolean isDebugEnabled() {
        return LogUtil.Level.DEBUG.atLeast(levelThreshold.get());
    }

    @Override
    public void log(LogUtil.Level level, String message, Object... args) {
        if (!level.atLeast(levelThreshold.get())) {
            return;
        }
        FormatResult result = args != null && args.length > 0
                ? format(message, args)
                : new FormatResult(String.valueOf(message), null);
        emit(level, result.text(), result.unconsumedThrowable());
    }

    @Override
    public void log(LogUtil.Level level, String message, Throwable throwable) {
        if (!level.atLeast(levelThreshold.get())) {
            return;
        }
        emit(level, String.valueOf(message), throwable);
    }

    @Override
    public PrintStream getStream(LogUtil.Level level) {
        return level == LogUtil.Level.WARN || level == LogUtil.Level.ERROR
                ? System.err
                : System.out;
    }

    private void emit(LogUtil.Level level, String text, Throwable throwable) {
        String line;
        if (tuiMode) {
            line = "[" + level.label() + "] " + text;
        } else {
            String time = LocalTime.now().format(TIME_FMT);
            line = String.format("%s[%s] [%s] [%s]%s %s",
                    level.ansiColor(), time, level.label(), name, RESET, text);
        }
        coordinator.writeLog(getStream(level), line, throwable);
    }

    private record FormatResult(String text, Throwable unconsumedThrowable) {
    }

    /** 将 SLF4J 风格 {@code {}} 占位符替换为对应参数，并识别尾部未消费的异常。 */
    private static FormatResult format(String template, Object... args) {
        String tpl = template == null ? "null" : template;
        StringBuilder sb = new StringBuilder(tpl.length() + args.length * 16);
        int argIdx = 0;
        int cursor = 0;
        while (cursor < tpl.length() && argIdx < args.length) {
            int idx = tpl.indexOf("{}", cursor);
            if (idx < 0) {
                break;
            }
            sb.append(tpl, cursor, idx);
            Object v = args[argIdx++];
            sb.append(v != null ? v : "null");
            cursor = idx + 2;
        }
        sb.append(tpl.substring(cursor));

        Throwable throwable = null;
        if (argIdx < args.length && args[args.length - 1] instanceof Throwable t) {
            throwable = t;
        }
        return new FormatResult(sb.toString(), throwable);
    }
}
