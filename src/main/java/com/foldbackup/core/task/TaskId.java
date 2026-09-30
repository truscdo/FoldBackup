package com.foldbackup.core.task;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Value type representing a unique task identifier.
 * <p>
 * Format: {@code <type-prefix>-<global-sequence>}, e.g. {@code bak-7}.
 * Created by {@link TaskRegistry#startTask(TaskType)}.
 */
public final class TaskId implements Comparable<TaskId> {

    private static final AtomicLong counter = new AtomicLong(0);

    private final String id;

    TaskId(TaskType type) {
        this.id = type.prefix() + "-" + counter.incrementAndGet();
    }

    /** The full string identifier, e.g. {@code "bak-7"}. */
    public String value() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TaskId that && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public int compareTo(TaskId other) {
        return id.compareTo(other.id);
    }
}
