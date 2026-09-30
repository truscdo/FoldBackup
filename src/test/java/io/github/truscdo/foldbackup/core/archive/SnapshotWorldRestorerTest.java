package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotWorldRestorerTest {
    @TempDir Path temp;

    @Test
    void rejectsConflictingTargetsBeforeReadingBlobsOrCreatingOutput() {
        var snapshot = new SnapshotManifest("snapshot", 1, "world");
        snapshot.addRegion(new RegionEntry("region/r.0.0.mca", "hash", "missing.lrh", 0, 0));
        snapshot.addExtraFile(new FileEntry("REGION", "hash", "missing.zst", 0, 0));
        Path output = temp.resolve("world");

        var restorer = new SnapshotWorldRestorer(new BlobStore(temp, 6), 6, 2);
        IOException failure = assertThrows(IOException.class, () -> restorer.restore(snapshot, output, null));
        assertTrue(failure.getMessage().contains("File/directory conflict"));
        assertFalse(Files.exists(output));
    }
}
