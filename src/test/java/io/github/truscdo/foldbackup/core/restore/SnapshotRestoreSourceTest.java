package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotRestoreSourceTest {

    @TempDir
    Path temp;

    @Test
    void readRegionsPropagatesIoRuntimeAndErrorDirectly() throws IOException {
        SnapshotManifest manifest = new SnapshotManifest("S-20261120_120000", 1795176000L, "world");
        manifest.getRegions().add(new RegionEntry("region/r.0.0.mca", "abc", "blobs/ab/c.lrh", 10L, 20L));
        try (var source = new SnapshotRestoreSource(manifest, new BlobStore(temp, 6))) {
            IOException io = new IOException("read failure");
            assertSame(io, assertThrows(IOException.class,
                    () -> source.readRegions(source.entries(), (entry, sink) -> { throw io; }, 1)));

            IllegalStateException runtime = new IllegalStateException("bug");
            assertSame(runtime, assertThrows(IllegalStateException.class,
                    () -> source.readRegions(source.entries(), (entry, sink) -> { throw runtime; }, 1)));

            AssertionError error = new AssertionError("fatal");
            assertSame(error, assertThrows(AssertionError.class,
                    () -> source.readRegions(source.entries(), (entry, sink) -> { throw error; }, 1)));
        }
    }
}
