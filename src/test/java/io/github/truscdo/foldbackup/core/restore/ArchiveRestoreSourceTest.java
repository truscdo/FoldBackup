package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.cli.SnapshotCreation;
import io.github.truscdo.foldbackup.core.archive.ArchiveCreator;
import io.github.truscdo.foldbackup.core.archive.ArchiveRestoreSource;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Archive-backed restore: full archive and differential archive through the shared skeleton. */
class ArchiveRestoreSourceTest {
    @TempDir Path temp;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Path world() throws IOException {
        Path world = temp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        Region region = new Region(0, 0, 0);
        region.setChunk(0, new ChunkData(bytes("chunk-v1"), 0, 0));
        region.setTimestamp(0, 1000);
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);
        Files.writeString(world.resolve("level.dat"), "level-v1");
        Files.writeString(world.resolve("playerdata/a.dat"), "player-a-v1");
        return world;
    }

    private void mutate(Path world) throws IOException {
        Region region = AnvilTestSupport.readRegionAnvil(world.resolve("region/r.0.0.mca"));
        region.setChunk(0, new ChunkData(bytes("chunk-v2"), 0, 0));
        region.setTimestamp(0, 2000);
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);
        Files.writeString(world.resolve("level.dat"), "level-v2");
        Files.delete(world.resolve("playerdata/a.dat"));
        Files.writeString(world.resolve("playerdata/b.dat"), "player-b");
    }

    private RestoreOperation.Outcome restore(Path archives, Path tar, Path output, boolean deleteExtra)
            throws IOException {
        try (var source = ArchiveRestoreSource.open(archives, tar)) {
            return RestoreOperation.run(source, output,
                    new RestoreOperation.Options(false, deleteExtra, 1, 6), null);
        }
    }

    @Test
    void restoresFullArchiveIntoNewDirectory() throws IOException {
        Path world = world();
        Path store = temp.resolve("store");
        Path archives = temp.resolve("archives");
        SnapshotManifest snapshot = SnapshotCreation.create(world, store, 1, event -> { });
        Path full = new ArchiveCreator(new SnapshotStore(store), new BlobStore(store, 6), 6)
                .createFull(snapshot.getEpochSeconds(), archives);

        Path output = temp.resolve("out");
        var outcome = restore(archives, full, output, false);
        assertEquals(1, outcome.stats().changedRegions());
        assertEquals(2, outcome.stats().changedFiles());
        assertEquals("level-v1", Files.readString(output.resolve("level.dat")));
        assertEquals("player-a-v1", Files.readString(output.resolve("playerdata/a.dat")));
        assertTrue(Files.exists(output.resolve("region/r.0.0.mca")));
    }

    @Test
    void appliesDifferentialArchiveAndDeletesOnlyWhenAsked() throws Exception {
        Path world = world();
        Path store = temp.resolve("store");
        Path archives = temp.resolve("archives");
        SnapshotManifest base = SnapshotCreation.create(world, store, 1, event -> { });
        Path full = new ArchiveCreator(new SnapshotStore(store), new BlobStore(store, 6), 6)
                .createFull(base.getEpochSeconds(), archives);

        Thread.sleep(1100);
        mutate(world);
        SnapshotManifest target = SnapshotCreation.create(world, store, 1, event -> { });
        Path diff = new ArchiveCreator(new SnapshotStore(store), new BlobStore(store, 6), 6)
                .createDiff(base.getEpochSeconds(), target.getEpochSeconds(), archives);

        Path output = temp.resolve("out");
        restore(archives, full, output, false);

        var kept = restore(archives, diff, output, false);
        assertEquals("level-v2", Files.readString(output.resolve("level.dat")));
        assertEquals("player-b", Files.readString(output.resolve("playerdata/b.dat")));
        assertEquals(1, kept.stats().deletedFiles());
        assertTrue(Files.exists(output.resolve("playerdata/a.dat")), "extra content is kept by default");

        restore(archives, diff, output, true);
        assertFalse(Files.exists(output.resolve("playerdata/a.dat")));
    }

    @Test
    void secondRestoreSkipsEverything() throws IOException {
        Path world = world();
        Path store = temp.resolve("store");
        Path archives = temp.resolve("archives");
        SnapshotManifest snapshot = SnapshotCreation.create(world, store, 1, event -> { });
        Path full = new ArchiveCreator(new SnapshotStore(store), new BlobStore(store, 6), 6)
                .createFull(snapshot.getEpochSeconds(), archives);

        Path output = temp.resolve("out");
        restore(archives, full, output, false);
        var second = restore(archives, full, output, false);
        assertEquals(0, second.stats().changedRegions());
        assertEquals(0, second.stats().changedFiles());
    }
}
