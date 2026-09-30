package io.github.truscdo.foldbackup.core;

import java.util.Objects;

/**
 * A stage-local progress snapshot emitted by long-running operations.
 *
 * @param phase     human-readable stage name
 * @param completed number of completed items in the current stage
 * @param total     total items in the current stage, or {@code -1} if unknown
 * @param item      item currently being processed, possibly empty
 */
public record ProgressEvent(String phase, long completed, long total, String item) {

    public ProgressEvent {
        Objects.requireNonNull(phase, "phase");
        if (completed < 0) {
            throw new IllegalArgumentException("completed must be non-negative");
        }
        if (total < -1) {
            throw new IllegalArgumentException("total must be -1 or non-negative");
        }
        if (total >= 0 && completed > total) {
            throw new IllegalArgumentException("completed must not exceed total");
        }
        item = item == null ? "" : item;
    }
}
