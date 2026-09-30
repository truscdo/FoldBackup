package com.foldbackup.core.task;

/**
 * Immutable progress snapshot for a single sub-step of a tracked task.
 * <p>
 * Each task type defines a sealed interface that extends this with
 * typed records for every sub-step. Consumers use {@code instanceof}
 * pattern matching to extract structured fields and format them for each UI.
 */
public interface StepProgress {
    /** Stable phase identifier, e.g. {@code "SCANNING_WORLD"}; not display text. */
    String phase();
}
