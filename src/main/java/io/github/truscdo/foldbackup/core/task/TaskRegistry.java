package io.github.truscdo.foldbackup.core.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for all active (and recently-completed) tasks.
 * <p>
 * Usage:
 * <pre>{@code
 * TaskRegistry registry = new TaskRegistry();
 *
 * // Worker thread creates a task
 * TaskStatus status = registry.startTask(TaskType.BACKUP);
 * status.setRunning().setMessage("Scanning...");
 * // ... do work ...
 * registry.complete(status);
 *
 * // Consumer thread reads state
 * TaskStatus s = registry.getCurrentTask();
 * if (s != null) { ... }
 * }</pre>
 */
public class TaskRegistry {

    private static final int MAX_HISTORY = 20;

    private final ConcurrentHashMap<String, TaskStatus> active = new ConcurrentHashMap<>();
    private final List<TaskStatus> completed = Collections.synchronizedList(new ArrayList<>());

    // ── Registration ──

    /**
     * Create and register a new task in {@link TaskStatus.Phase#PENDING} state.
     *
     * @return the newly created task status (already registered)
     */
    public TaskStatus startTask(TaskType type) {
        TaskId taskId = new TaskId(type);
        TaskStatus status = new TaskStatus(taskId, type);
        active.put(taskId.value(), status);
        return status;
    }

    // ── Completion / failure ──

    /**
     * Move a task from active to completed history.
     */
    public void complete(String taskId) {
        TaskStatus status = active.remove(taskId);
        if (status != null) {
            status.setCompleted();
            addToHistory(status);
        }
    }

    /**
     * Move a task from active to failed history.
     */
    public void fail(String taskId, String errorMessage) {
        TaskStatus status = active.remove(taskId);
        if (status != null) {
            status.setFailed(errorMessage);
            addToHistory(status);
        }
    }

    /**
     * Mark a task as failed. Overload that accepts the status directly
     * (useful when the caller already holds the reference).
     */
    public void fail(TaskStatus status, String errorMessage) {
        active.remove(status.taskId().value());
        status.setFailed(errorMessage);
        addToHistory(status);
    }

    /**
     * Mark a task as completed. Overload that accepts the status directly.
     */
    public void complete(TaskStatus status) {
        active.remove(status.taskId().value());
        status.setCompleted();
        addToHistory(status);
    }

    // ── Queries ──

    /** Look up a single task by its ID (active or completed). */
    public TaskStatus get(String taskId) {
        TaskStatus s = active.get(taskId);
        if (s != null) return s;
        synchronized (completed) {
            int idx = indexInHistory(taskId);
            return idx >= 0 ? completed.get(idx) : null;
        }
    }

    /**
     * Return the most recently started <em>active</em> task, or {@code null}
     * if no task is currently running.
     */
    public TaskStatus getCurrentActive() {
        // There's typically at most one — iterate values
        for (TaskStatus s : active.values()) {
            return s;
        }
        return null;
    }

    /** Return {@code true} when at least one task is active. */
    public boolean hasActiveTask() {
        return !active.isEmpty();
    }

    /**
     * Return an unmodifiable snapshot of recently completed tasks
     * (most recent first).
     */
    public List<TaskStatus> getHistory() {
        synchronized (completed) {
            return List.copyOf(completed);
        }
    }

    // ── Internals ──

    private void addToHistory(TaskStatus status) {
        synchronized (completed) {
            completed.add(0, status);  // most recent first
            if (completed.size() > MAX_HISTORY) {
                completed.remove(completed.size() - 1);
            }
        }
    }

    private int indexInHistory(String taskId) {
        // completed is already most-recent-first
        for (int i = 0; i < completed.size(); i++) {
            if (completed.get(i).taskId().value().equals(taskId)) {
                return i;
            }
        }
        return -1;
    }
}
