package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveRestorePipelineTest {

    private static ArchiveRestorePipeline singleRegionPipeline() {
        var region = new ArchiveRestorePlan.Region("region/r.0.0.mca", null, null, 0, 0,
                "hash", 0L, 0, SourceMetadata.empty());
        return new ArchiveRestorePipeline(List.of(region), List.of());
    }

    @Test
    void propagatesIoRuntimeAndErrorWithoutWrapping() {
        IOException io = new IOException("corrupt region");
        assertSame(io, assertThrows(IOException.class, () -> singleRegionPipeline().run(
                ArchiveTasks.TaskWork.decompress(), 1, null, i -> true, null, (i, source) -> { throw io; })));

        IllegalStateException runtime = new IllegalStateException("unexpected state");
        assertSame(runtime, assertThrows(IllegalStateException.class, () -> singleRegionPipeline().run(
                ArchiveTasks.TaskWork.decompress(), 1, null, i -> true, null, (i, source) -> { throw runtime; })));

        AssertionError error = new AssertionError("fatal error");
        assertSame(error, assertThrows(AssertionError.class, () -> singleRegionPipeline().run(
                ArchiveTasks.TaskWork.decompress(), 1, null, i -> true, null, (i, source) -> { throw error; })));

        for (Throwable failure : new Throwable[] {io, runtime, error}) {
            assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(s ->
                            s instanceof ArchiveRestorePipeline.RegionContext
                                    && s.getMessage().contains("region/r.0.0.mca")),
                    () -> "missing region path context on " + failure);
        }
    }
}
