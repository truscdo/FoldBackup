package io.github.truscdo.foldbackup.core;

/** Emits consistent stage-local progress events for core operations. */
public final class ProgressTracker {

    private final ProgressListener listener;
    private String phase = "";
    private long completed;
    private long total = -1;

    public ProgressTracker(ProgressListener listener) {
        this.listener = listener;
    }

    public void stage(String phase, long total) {
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase must not be blank");
        }
        if (total < 0 && total != -1) {
            throw new IllegalArgumentException("total must be -1 or non-negative");
        }
        this.phase = phase;
        this.completed = 0;
        this.total = total;
        emit("");
    }

    public void current(String item) {
        emit(item);
    }

    public void done(String item) {
        if (total >= 0 && completed >= total) {
            throw new IllegalStateException("Progress stage is already complete: " + phase);
        }
        completed++;
        emit(item);
    }

    private void emit(String item) {
        if (listener != null) {
            listener.onProgress(new ProgressEvent(phase, completed, total, item));
        }
    }
}
