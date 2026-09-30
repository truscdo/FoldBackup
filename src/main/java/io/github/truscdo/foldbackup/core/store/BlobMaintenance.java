package io.github.truscdo.foldbackup.core.store;

import io.github.truscdo.foldbackup.core.LogUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Set;

/**
 * Filesystem maintenance for the content-addressed blob area.
 *
 * <p>This class owns the {@code blobs/} directory walk and temporary
 * {@code .wip} cleanup. Snapshot liveness decisions remain with the snapshot
 * index; callers provide the set of active relative blob paths.</p>
 */
public class BlobMaintenance {
    public record GcResult(int deletedFiles, long reclaimedBytes, int failedFiles, boolean scanComplete) {}

    private static final LogUtil LOG = LogUtil.getLogger(BlobMaintenance.class);

    private final Path rootDir;

    public BlobMaintenance(Path rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * Delete regular files below {@code blobs/} that are not in the active set.
     * The caller must hold the directory write session while loading the active
     * index and performing this sweep (see {@link SnapshotStore#collectGarbage()}).
     *
     * @return number of deleted files
     */
    public int deleteOrphans(Set<String> activeBlobPaths) {
        return deleteOrphansDetailed(activeBlobPaths).deletedFiles();
    }

    public GcResult deleteOrphansDetailed(Set<String> activeBlobPaths) {
        Set<String> active = activeBlobPaths != null ? activeBlobPaths : Collections.emptySet();
        Path blobsDir = rootDir.resolve("blobs");
        if (!Files.isDirectory(blobsDir)) {
            return new GcResult(0, 0, 0, Files.notExists(blobsDir));
        }

        int deleted = 0;
        int failed = 0;
        long reclaimed = 0;
        boolean complete = true;
        try (var files = Files.walk(blobsDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relPath = rootDir.relativize(file).toString().replace('\\', '/');
                if (!active.contains(relPath)) {
                    try {
                        long size = Files.size(file);
                        long nextReclaimed = Math.addExact(reclaimed, size);
                        if (deleteOrphan(file)) {
                            deleted++;
                            reclaimed = nextReclaimed;
                        }
                    } catch (IOException | ArithmeticException e) {
                        failed++;
                        LOG.debug("Could not delete orphan blob: {}", relPath);
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            complete = false;
            LOG.warn("Failed to walk blob store for orphan scan", e);
        }
        return new GcResult(deleted, reclaimed, failed, complete);
    }

    protected boolean deleteOrphan(Path file) throws IOException {
        return Files.deleteIfExists(file);
    }

    /** Delete stale temporary files created by atomic storage operations. */
    public int cleanupWipFiles() {
        try {
            BackupDirectoryLock lock = BackupDirectoryLock.forRoot(rootDir);
            lock.lock(this);
            try {
                return cleanupWipFilesLocked();
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            LOG.warn("Could not lock backup directory for temporary blob cleanup", e);
            return 0;
        }
    }

    private int cleanupWipFilesLocked() {
        int deleted = 0;
        try (var files = Files.walk(rootDir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (file.getFileName().toString().endsWith(".wip")) {
                    try {
                        if (Files.deleteIfExists(file)) {
                            deleted++;
                        }
                    } catch (IOException e) {
                        LOG.debug("Could not delete temporary blob file: {}", file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not scan for temporary blob files", e);
        }
        return deleted;
    }
}
