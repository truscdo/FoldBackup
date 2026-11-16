package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.ProgressEvent;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 统一的进度与状态消息节流器。
 * <p>
 * 集中收敛进度渲染、备份扫描心跳、服务端任务日志与玩家聊天进度推送的限流策略，
 * 统一使用单调时钟（纳秒）避免受墙钟跳变影响。所有方法均为线程安全。
 */
public final class ProgressThrottler {

    /** 默认的 1 秒节流窗口（纳秒）。 */
    public static final long DEFAULT_INTERVAL_NANOS = 1_000_000_000L;

    private final LongSupplier clockNanos;
    private final long defaultIntervalNanos;
    private final boolean deduplicateContent;

    private boolean hasEmitted;
    private long lastEmitNanos;
    private String lastPhase;
    private boolean awaitingFirstItem;
    private String lastContent;

    public ProgressThrottler(long intervalNanos, boolean deduplicateContent) {
        this(System::nanoTime, intervalNanos, deduplicateContent);
    }

    public ProgressThrottler(LongSupplier clockNanos, long intervalNanos, boolean deduplicateContent) {
        if (intervalNanos < 0) {
            throw new IllegalArgumentException("intervalNanos must be non-negative: " + intervalNanos);
        }
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
        this.defaultIntervalNanos = intervalNanos;
        this.deduplicateContent = deduplicateContent;
    }

    /**
     * 判断结构化进度事件是否应当放行渲染。
     * <p>
     * 阶段切换、阶段首条非空条目与阶段完成（{@code completed == total}）会穿透时间窗立即放行；
     * 若开启内容去重，仅对同阶段、非终态且尚未到达时间窗或完全重复内容生效。
     */
    public synchronized boolean allowProgress(ProgressEvent event) {
        if (event == null) {
            return false;
        }
        long now = clockNanos.getAsLong();
        boolean stageChanged = !event.phase().equals(lastPhase);
        boolean completed = event.total() >= 0 && event.completed() >= event.total();

        if (stageChanged) {
            lastPhase = event.phase();
            awaitingFirstItem = event.item().isEmpty();
            recordEmit(now, event.item());
            return true;
        }
        if (completed) {
            awaitingFirstItem = false;
            if (deduplicateContent && lastContent != null && lastContent.equals(event.item()) && hasEmitted) {
                return false;
            }
            recordEmit(now, event.item());
            return true;
        }
        if (awaitingFirstItem && !event.item().isEmpty()) {
            awaitingFirstItem = false;
            recordEmit(now, event.item());
            return true;
        }
        if (!hasEmitted || now - lastEmitNanos >= defaultIntervalNanos) {
            awaitingFirstItem = event.item().isEmpty() && awaitingFirstItem;
            if (deduplicateContent && event.item().equals(lastContent)) {
                return false;
            }
            recordEmit(now, event.item());
            return true;
        }
        return false;
    }

    /**
     * 判断文本状态消息（如任务日志、聊天进度推送）是否应当发送。
     *
     * @param message 待发送文本；空串或 {@code null} 一律不发送
     * @param force   为 {@code true} 时忽略时间间隔限制（仍受 {@code deduplicateContent} 约束）
     */
    public synchronized boolean allowMessage(String message, boolean force) {
        return allowMessage(message, defaultIntervalNanos, force);
    }

    /**
     * 判断文本状态消息在动态时间间隔下是否应当发送。
     */
    public synchronized boolean allowMessage(String message, long intervalNanos, boolean force) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        if (deduplicateContent && message.equals(lastContent)) {
            return false;
        }
        long now = clockNanos.getAsLong();
        if (!force && hasEmitted && now - lastEmitNanos < Math.max(0L, intervalNanos)) {
            return false;
        }
        recordEmit(now, message);
        return true;
    }

    /**
     * 纯时间窗限流（供无需对比内容的采样器使用）。
     *
     * @param force 为 {@code true} 时跳过时间窗立即放行
     */
    public synchronized boolean tryAcquire(boolean force) {
        long now = clockNanos.getAsLong();
        if (!force && hasEmitted && now - lastEmitNanos < defaultIntervalNanos) {
            return false;
        }
        recordEmit(now, null);
        return true;
    }

    /** 在外部心跳刷新后同步记录发射时刻，避免立刻重复触发。 */
    public synchronized void markEmitted() {
        this.lastEmitNanos = clockNanos.getAsLong();
        this.hasEmitted = true;
    }

    /** 重置节流状态（例如任务切换或清空订阅者时）。 */
    public synchronized void reset() {
        this.hasEmitted = false;
        this.lastEmitNanos = 0L;
        this.lastPhase = null;
        this.awaitingFirstItem = false;
        this.lastContent = null;
    }

    private void recordEmit(long now, String content) {
        this.lastEmitNanos = now;
        this.hasEmitted = true;
        this.lastContent = content;
    }
}
