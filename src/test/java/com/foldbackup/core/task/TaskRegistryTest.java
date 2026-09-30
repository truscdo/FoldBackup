package com.foldbackup.core.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskRegistryTest {
    @Test void latestCompletedOrFailedTaskIsFirstInHistory() {
        var registry = new TaskRegistry();
        var first = registry.startTask(TaskType.BACKUP);
        first.setArtifact(123, "first-snapshot");
        registry.complete(first);
        var capturedHistory = registry.getHistory();
        var second = registry.startTask(TaskType.BACKUP);
        registry.fail(second, "write failed");
        assertSame(second, registry.getHistory().getFirst());
        assertEquals(TaskStatus.Phase.FAILED, registry.getHistory().getFirst().phase());
        assertSame(first, registry.getHistory().get(1));
        assertEquals(123L, first.artifactEpochSeconds());
        assertEquals("first-snapshot", first.artifactId());
        assertEquals(1, capturedHistory.size());
        assertThrows(UnsupportedOperationException.class, () -> capturedHistory.clear());
    }
}
