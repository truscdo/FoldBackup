package io.github.truscdo.foldbackup.core.logging;

import java.io.PrintStream;
import java.util.Objects;

/**
 * CLI/TUI 控制台输出协调器。
 * <p>
 * 解决终端下“单行 {@code \r} 原地刷新进度条”与“异步日志输出”相互踩踏导致的
 * 字符交错、进度条被撕裂的问题。所有经过本协调器的日志打印与进度条渲染保持严格互斥：
 * <ul>
 * <li>当存在活跃进度条时，输出日志前先擦除当前进度行，写完日志后再立即重绘进度条；</li>
 * <li>进度渲染与心跳刷新通过 {@link #runExclusive(Runnable)} 持锁执行，防止多线程字节交错。</li>
 * </ul>
 * 锁顺序约定：始终先获取 {@code ConsoleOutputCoordinator} 内部锁，再获取进度渲染器内部锁。
 */
public final class ConsoleOutputCoordinator {

    /** 面向当前 JVM 标准控制台的全局共享协调器。 */
    private static final ConsoleOutputCoordinator SYSTEM = new ConsoleOutputCoordinator();

    /** 活跃单行进度渲染器的回调协议。 */
    public interface ProgressHook {
        /** 擦除当前未换行的 {@code \r} 进度行（若尚未渲染或已换行则为空操作）。 */
        void clearLineForLog();

        /** 在日志换行输出后恢复重绘当前最新进度行。 */
        void restoreLineAfterLog();
    }

    private final Object lock = new Object();
    private ProgressHook activeProgress;

    public ConsoleOutputCoordinator() {
    }

    public static ConsoleOutputCoordinator system() {
        return SYSTEM;
    }

    /** 注册当前活动的进度条渲染器（通常在 {@code ProgressPrinter} 打开时调用）。 */
    public void register(ProgressHook hook) {
        Objects.requireNonNull(hook, "hook");
        synchronized (lock) {
            activeProgress = hook;
        }
    }

    /** 注销进度条渲染器（仅当仍为同一实例时清空）。 */
    public void unregister(ProgressHook hook) {
        synchronized (lock) {
            if (activeProgress == hook) {
                activeProgress = null;
            }
        }
    }

    /** 在协调器互斥保护下执行渲染或收尾动作。 */
    public void runExclusive(Runnable action) {
        Objects.requireNonNull(action, "action");
        synchronized (lock) {
            action.run();
        }
    }

    /**
     * 安全输出一条日志（及可选异常堆栈），若当前有活跃进度条则先擦除再恢复重绘。
     */
    public void writeLog(PrintStream stream, String formattedLine, Throwable throwable) {
        Objects.requireNonNull(stream, "stream");
        synchronized (lock) {
            ProgressHook hook = activeProgress;
            if (hook != null) {
                hook.clearLineForLog();
            }
            try {
                stream.println(formattedLine);
                if (throwable != null) {
                    throwable.printStackTrace(stream);
                }
                stream.flush();
            } finally {
                if (hook != null) {
                    hook.restoreLineAfterLog();
                }
            }
        }
    }
}
