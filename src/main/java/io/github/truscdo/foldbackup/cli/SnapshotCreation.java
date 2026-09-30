package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.console.BackupProgressAdapter;
import io.github.truscdo.foldbackup.core.backup.BackupContext;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.backup.BackupFilter;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared offline snapshot creation for CLI and TUI. */
public final class SnapshotCreation {
    private static final int COMPRESSION_LEVEL = 6;

    private SnapshotCreation() {}

    public static SnapshotManifest create(Path world, Path root, int threads, ProgressListener listener)
            throws IOException {
        if (threads < 0) throw new IOException("Threads must be non-negative");
        world = world.toAbsolutePath().normalize();
        if (!Files.isDirectory(world)) throw new IOException("World directory not found: " + world);
        try (WorldSaveLock ignored = WorldSaveLock.acquireRoot(world)) {
            var snapshots = new SnapshotStore(root);
            var blobs = new BlobStore(root, COMPRESSION_LEVEL);
            var processor = new RegionProcessor(snapshots::getIndex, blobs, COMPRESSION_LEVEL,
                    BackupFilter.defaults(), threads, 0);
            String name = world.getFileName() == null ? "world" : world.getFileName().toString();
            var coordinator = new BackupCoordinator(new BackupContext(world, name, ""),
                    snapshots, processor, null);
            var registry = new TaskRegistry();
            var status = registry.startTask(TaskType.BACKUP).setRunning();
            try {
                var manifest = coordinator.runBackup(status, BackupProgressAdapter.toListener(listener));
                registry.complete(status);
                return manifest;
            } catch (IOException | RuntimeException e) {
                registry.fail(status, e.getMessage());
                throw e;
            }
        }
    }

    public static String summary(SnapshotManifest snapshot, Path root) {
        return "Created snapshot " + snapshot.getSnapshotId() + " in " + root
                + " (" + snapshot.getRegions().size() + " regions, "
                + snapshot.getExtraFiles().size() + " files)";
    }
}
