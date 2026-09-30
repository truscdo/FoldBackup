package com.foldbackup.core.task;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable progress status for one tracked asynchronous task.
 * <p>
 * Created by {@link TaskRegistry#startTask(TaskType)} and shared between
 * the worker thread (which writes) and consumer threads (which read).
 * All mutable fields use {@code volatile} or {@link AtomicInteger} for
 * safe cross-thread visibility without locking.
 */
public class TaskStatus {

    // ──────────────────────────────────────────────
    // Phase — lifecycle states common to all task types
    // ──────────────────────────────────────────────

    public enum Phase {
        /** Task has been registered but not yet started execution. */
        PENDING,
        /** Task is actively executing. */
        RUNNING,
        /** Task completed successfully. */
        COMPLETED,
        /** Task terminated with an error. */
        FAILED
    }

    // ── Immutable identity ──

    private final TaskId taskId;
    private final TaskType type;
    private final long createdAtMs;

    // ── Mutable progress (visible across threads) ──

    private volatile Phase phase;
    private volatile String message;
    /** Current sub-step progress (typed via sealed {@link StepProgress} hierarchy). */
    private volatile StepProgress stepProgress;
    private volatile long completedAtMs;
    /** Internal snapshot artifact identity, present for tasks that create a snapshot. */
    private volatile Long artifactEpochSeconds;
    /** User-facing artifact label; presentation only. */
    private volatile String artifactId;
    /** 0-100, or -1 when unknown. */
    private final AtomicInteger progressPercent = new AtomicInteger(-1);

    // package-private constructor — only TaskRegistry creates these
    TaskStatus(TaskId taskId, TaskType type) {
        this.taskId = taskId;
        this.type = type;
        this.createdAtMs = System.currentTimeMillis();
        this.phase = Phase.PENDING;
        this.message = "";
    }

    // ── Readers (called from any thread) ──

    public TaskId taskId()       { return taskId; }
    public TaskType type()       { return type; }
    public long createdAtMs()    { return createdAtMs; }
    public Phase phase()         { return phase; }
    public String message()      { return message; }
    public long completedAtMs()  { return completedAtMs; }
    public Long artifactEpochSeconds() { return artifactEpochSeconds; }
    /** Returns the presentation label for the task artifact, if any. */
    public String artifactId()   { return artifactId; }
    public int progressPercent() { return progressPercent.get(); }

    public StepProgress stepProgress() { return stepProgress; }

    public boolean isActive() {
        Phase p = phase;
        return p == Phase.PENDING || p == Phase.RUNNING;
    }

    public boolean isTerminal() {
        Phase p = phase;
        return p == Phase.COMPLETED || p == Phase.FAILED;
    }

    public String elapsedSinceCreated() {
        long elapsed = (phase == Phase.PENDING || phase == Phase.RUNNING)
                ? System.currentTimeMillis() - createdAtMs
                : completedAtMs - createdAtMs;
        long secs = elapsed / 1000;
        if (secs < 60) return secs + "s";
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    // ── Writers (called from the worker thread) ──

    public TaskStatus setRunning() {
        this.phase = Phase.RUNNING;
        return this;
    }

    public TaskStatus setMessage(String message) {
        this.message = message;
        return this;
    }

    public TaskStatus setProgressPercent(int pct) {
        this.progressPercent.set(pct);
        return this;
    }

    public TaskStatus setStepProgress(StepProgress stepProgress) {
        this.stepProgress = stepProgress;
        return this;
    }

    public TaskStatus setArtifact(long epochSeconds, String artifactId) {
        this.artifactEpochSeconds = epochSeconds;
        this.artifactId = artifactId;
        return this;
    }

    public TaskStatus setArtifactLabel(String artifactId) {
        this.artifactId = artifactId;
        return this;
    }

    /**
     * Mark the task as completed, record the completion time and optional
     * artifact. Once called the status is immutable (no further updates).
     */
    public TaskStatus setCompleted() {
        this.phase = Phase.COMPLETED;
        this.completedAtMs = System.currentTimeMillis();
        return this;
    }

    /**
     * Mark the task as failed, record the error message and completion time.
     * Once called the status is immutable (no further updates).
     */
    public TaskStatus setFailed(String errorMessage) {
        this.phase = Phase.FAILED;
        this.message = errorMessage;
        this.completedAtMs = System.currentTimeMillis();
        return this;
    }

    @Override
    public String toString() {
        return taskId + " [" + type + "] " + phase + ": " + message;
    }
}
