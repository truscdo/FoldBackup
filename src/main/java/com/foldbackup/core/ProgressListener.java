package com.foldbackup.core;

/** Receives structured progress events from a long-running operation. */
@FunctionalInterface
public interface ProgressListener {
    void onProgress(ProgressEvent event);
}
