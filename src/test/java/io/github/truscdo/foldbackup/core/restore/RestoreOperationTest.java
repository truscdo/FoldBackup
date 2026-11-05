package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RestoreOperationTest {
    @TempDir Path temp;

    private Path world() throws IOException {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(new byte[] {1, 2, 3}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);
        return world;
    }

    private Path store() throws IOException {
        Path store = temp.resolve("store");
        SnapshotCreation.create(world(), store, 1, event -> { });
        return store;
    }

    private RestoreOperation.Outcome export(Path store, Path output, boolean deleteExtra, boolean dryRun)
            throws IOException {
        var snapshot = new io.github.truscdo.foldbackup.core.store.SnapshotStore(store).loadLatestSnapshot();
        try (var source = new SnapshotRestoreSource(snapshot, new BlobStore(store, 6))) {
            return RestoreOperation.run(source, output,
                    new RestoreOperation.Options(dryRun, deleteExtra, 1, 6), null);
        }
    }

    @Test
    void restoresIntoNewDirectoryAndSkipsOnSecondRun() throws IOException {
        Path store = store();
        Path output = temp.resolve("export");

        var first = export(store, output, false, false);
        assertEquals(1, first.stats().changedRegions());
        assertEquals(1, first.stats().changedFiles());
        assertEquals("world metadata", Files.readString(output.resolve("level.dat")));
        assertTrue(Files.exists(output.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(output.resolve("session.lock")));

        var second = export(store, output, false, false);
        assertEquals(0, second.stats().changedFiles());
        assertEquals(0, second.stats().changedRegions(), "second restore should reuse both entries");
        // The existing save is recognized, so the second run takes the world lock.
        assertTrue(Files.exists(output.resolve("session.lock")));
    }

    @Test
    void dryRunLeavesNoTrace() throws IOException {
        Path store = store();
        Path output = temp.resolve("dry");
        var outcome = export(store, output, true, true);
        assertEquals(1, outcome.stats().changedRegions());
        assertEquals(1, outcome.stats().changedFiles());
        assertFalse(Files.exists(output));
    }

    @Test
    void deletesExtraContentOnlyWhenRequested() throws IOException {
        Path store = store();
        Path output = temp.resolve("export");
        export(store, output, false, false);

        Path extra = output.resolve("data/extra.txt");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "extra");

        var kept = export(store, output, false, false);
        assertEquals(1, kept.stats().deletedFiles());
        assertTrue(Files.exists(extra));

        var removed = export(store, output, true, false);
        assertEquals(1, removed.stats().deletedFiles());
        assertFalse(Files.exists(extra));
    }

    @Test
    void dryRunOnExistingSaveTakesTheLockAndDeletesNothing() throws IOException {
        Path store = store();
        Path output = temp.resolve("export");
        export(store, output, false, false);
        Path extra = output.resolve("data/extra.txt");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "extra");

        var preview = export(store, output, true, true);

        assertEquals(1, preview.stats().deletedFiles());
        assertEquals(0, preview.stats().changedRegions());
        assertEquals(0, preview.stats().changedFiles());
        assertTrue(Files.exists(extra), "a dry run never deletes");
        // The save was recognized before the check, so the preview takes the world lock.
        assertTrue(Files.exists(output.resolve("session.lock")));
    }

    @Test
    void deleteExtraRemovesFullyEmptiedDirectoriesOnly() throws IOException {
        Path store = store();
        Path output = temp.resolve("export");
        export(store, output, false, false);

        Path orphan = Files.createDirectories(output.resolve("cache/deep"));
        Files.writeString(orphan.resolve("a.txt"), "a");
        Path shared = Files.createDirectories(output.resolve("data"));
        Files.writeString(shared.resolve("extra.txt"), "keep me");

        var removed = export(store, output, true, false);

        assertEquals(2, removed.stats().deletedFiles());
        assertTrue(removed.stats().emptiedDirectories().contains("cache"),
                removed.stats().emptiedDirectories().toString());
        assertFalse(Files.exists(output.resolve("cache")), "the emptied subtree is removed");
        assertFalse(Files.exists(output.resolve("data")), "the emptied directory is removed");
        assertTrue(Files.exists(output.resolve("level.dat")), "snapshot content stays");
    }
}
