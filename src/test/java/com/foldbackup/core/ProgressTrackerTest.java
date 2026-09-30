package com.foldbackup.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressTrackerTest {

    @Test
    void emitsStageCurrentAndCompletionEvents() {
        List<ProgressEvent> events = new ArrayList<>();
        var tracker = new ProgressTracker(events::add);

        tracker.stage("Copying files", 2);
        tracker.current("a.txt");
        tracker.done("a.txt");
        tracker.done("b.txt");

        assertEquals(List.of(
                new ProgressEvent("Copying files", 0, 2, ""),
                new ProgressEvent("Copying files", 0, 2, "a.txt"),
                new ProgressEvent("Copying files", 1, 2, "a.txt"),
                new ProgressEvent("Copying files", 2, 2, "b.txt")), events);
    }

    @Test
    void supportsUnknownTotalsAndRejectsInvalidStageTransitions() {
        List<ProgressEvent> events = new ArrayList<>();
        var tracker = new ProgressTracker(events::add);
        tracker.stage("Verifying", -1);
        tracker.done("entry");
        assertEquals(new ProgressEvent("Verifying", 1, -1, "entry"), events.get(1));

        assertThrows(IllegalArgumentException.class, () -> tracker.stage("", 1));
        tracker.stage("Done", 1);
        tracker.done("item");
        assertThrows(IllegalStateException.class, () -> tracker.done("extra"));
    }
}
