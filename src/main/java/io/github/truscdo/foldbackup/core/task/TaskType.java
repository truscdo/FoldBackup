package io.github.truscdo.foldbackup.core.task;

/**
 * Types of asynchronous operations tracked by {@link TaskRegistry}.
 * <p>
 * Each type has a short prefix used in {@link TaskId} generation:
 * <pre>
 *   BACKUP("bak")    → "bak-1", "bak-2", …
 *   DELETE("del")    → "del-1", "del-2", …
 * </pre>
 */
public enum TaskType {

    BACKUP("bak"),
    DELETE("del");

    private final String prefix;

    TaskType(String prefix) {
        this.prefix = prefix;
    }

    /** The short alphabetic prefix used in generated task IDs. */
    public String prefix() {
        return prefix;
    }
}
