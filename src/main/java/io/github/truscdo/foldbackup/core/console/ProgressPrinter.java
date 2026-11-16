package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.logging.ConsoleOutputCoordinator;
import io.github.truscdo.foldbackup.core.logging.ProgressThrottler;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 统一的 CLI/TUI 单行进度渲染器。
 * <p>
 * 特性：
 * <ul>
 * <li>通过 {@link ProgressThrottler} 实现阶段切换、首条条目与完成帧穿透的 1 秒节流；</li>
 * <li>可选 1 秒守护心跳，确保大任务在耗时单步（重压缩/索引提交/GC）期间秒表持续跳动；</li>
 * <li>关联标准流时自动注册到 {@link ConsoleOutputCoordinator}，避免日志与 {@code \r} 进度行交错。</li>
 * </ul>
 */
public final class ProgressPrinter implements AutoCloseable {

    private static final long DEFAULT_HEARTBEAT_MILLIS = 1_000L;

    private final PrintStream out;
    private final LongSupplier clock;
    private final ProgressThrottler throttler;
    private final ConsoleOutputCoordinator coordinator;
    private final ScheduledExecutorService heartbeat;
    private final ConsoleOutputCoordinator.ProgressHook hook;
    private final long started;

    private ProgressEvent lastEvent;
    private long lastRenderNanos;
    private int width;
    private boolean closed;

    public ProgressPrinter(PrintStream out) {
        this(out, System::nanoTime, isStandardStream(out) ? ConsoleOutputCoordinator.system() : null,
                true, DEFAULT_HEARTBEAT_MILLIS);
    }

    /** 确定性单元测试用构造器：使用注入时钟，不开启后台心跳且不注册全局协调器。 */
    ProgressPrinter(PrintStream out, LongSupplier clock) {
        this(out, clock, null, false, 0L);
    }

    /** 可注入协调器与心跳周期的高级/测试构造器。 */
    ProgressPrinter(PrintStream out, LongSupplier clock, ConsoleOutputCoordinator coordinator,
            boolean enableHeartbeat, long heartbeatPeriodMillis) {
        this.out = Objects.requireNonNull(out, "out");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.throttler = new ProgressThrottler(this.clock, ProgressThrottler.DEFAULT_INTERVAL_NANOS, false);
        this.coordinator = coordinator;
        this.started = this.clock.getAsLong();
        this.lastRenderNanos = this.started;
        this.hook = new ConsoleOutputCoordinator.ProgressHook() {
            @Override
            public void clearLineForLog() {
                synchronized (ProgressPrinter.this) {
                    ProgressPrinter.this.clearLineLocked();
                }
            }

            @Override
            public void restoreLineAfterLog() {
                synchronized (ProgressPrinter.this) {
                    ProgressPrinter.this.restoreLineLocked();
                }
            }
        };
        if (this.coordinator != null) {
            this.coordinator.register(this.hook);
        }
        if (enableHeartbeat && heartbeatPeriodMillis > 0) {
            this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "foldbackup-progress-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            this.heartbeat.scheduleAtFixedRate(this::heartbeatTick,
                    heartbeatPeriodMillis, heartbeatPeriodMillis, TimeUnit.MILLISECONDS);
        } else {
            this.heartbeat = null;
        }
    }

    private static boolean isStandardStream(PrintStream out) {
        return out == System.out || out == System.err;
    }

    public ProgressListener onProgress() {
        return this::print;
    }

    public void print(ProgressEvent event) {
        if (event == null) {
            return;
        }
        runGuarded(() -> {
            if (closed || !throttler.allowProgress(event)) {
                return;
            }
            lastEvent = event;
            renderLocked(event, clock.getAsLong());
        });
    }

    private void heartbeatTick() {
        try {
            runGuarded(() -> {
                if (closed || lastEvent == null) {
                    return;
                }
                long now = clock.getAsLong();
                if (now - lastRenderNanos < ProgressThrottler.DEFAULT_INTERVAL_NANOS) {
                    return;
                }
                renderLocked(lastEvent, now);
                throttler.markEmitted();
            });
        } catch (Throwable ignored) {
            // 守护心跳线程不得因偶发渲染异常中止后续刷新。
        }
    }

    private void renderLocked(ProgressEvent event, long now) {
        String item = event.item().replaceAll("[\\p{Cntrl}]", "?");
        if (item.length() > 80) {
            item = "..." + item.substring(item.length() - 77);
        }
        String count = event.total() < 0
                ? event.completed() + "/?"
                : event.completed() + "/" + event.total();
        String percent = event.total() < 0
                ? " --%"
                : String.format("%3d%%", event.total() == 0
                        ? 0 : Math.min(100, event.completed() * 100 / event.total()));
        String line = String.format("  [%s] %s %s | %ds | %s", percent, event.phase(), count,
                Math.max(0, (now - started) / 1_000_000_000L), item);
        out.print("\r" + line + " ".repeat(Math.max(0, width - line.length())));
        out.flush();
        width = line.length();
        lastRenderNanos = now;
    }

    private void clearLineLocked() {
        if (closed || width <= 0) {
            return;
        }
        out.print("\r" + " ".repeat(width) + "\r");
        out.flush();
        width = 0;
    }

    private void restoreLineLocked() {
        if (closed || lastEvent == null) {
            return;
        }
        renderLocked(lastEvent, clock.getAsLong());
        throttler.markEmitted();
    }

    @Override
    public void close() {
        runGuarded(() -> {
            if (closed) {
                return;
            }
            closed = true;
            if (width > 0) {
                out.println();
                out.flush();
                width = 0;
            }
        });
        if (coordinator != null) {
            coordinator.unregister(hook);
        }
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    private void runGuarded(Runnable action) {
        if (coordinator != null) {
            coordinator.runExclusive(() -> {
                synchronized (this) {
                    action.run();
                }
            });
        } else {
            synchronized (this) {
                action.run();
            }
        }
    }
}
