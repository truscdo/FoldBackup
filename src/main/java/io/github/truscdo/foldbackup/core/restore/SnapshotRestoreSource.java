package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.format.AnvilFormat;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Restore source backed by an online snapshot and its content-addressed blobs. */
public final class SnapshotRestoreSource implements RestoreSource {
    private final BlobStore blobs;
    private final List<RestoreEntry> entries;
    private final Map<String, String> blobPaths = new HashMap<>();

    public SnapshotRestoreSource(SnapshotManifest snapshot, BlobStore blobs) throws IOException {
        this.blobs = blobs;
        List<RestoreEntry> all = new ArrayList<>();
        if (snapshot.getRegions() != null) {
            for (RegionEntry region : snapshot.getRegions()) {
                String path = RestorePaths.validate(region.getFilename());
                String name = path.substring(path.lastIndexOf('/') + 1);
                int[] coords;
                try {
                    coords = AnvilFormat.parseRegionCoords(name);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid region coordinates: " + path, e);
                }
                all.add(RestoreEntry.region(path, coords[0], coords[1], region.getContentHash(),
                        region.getSizeUncompressed(), region.getSourceMetadata()));
                blobPaths.put(path, region.getBlobPath());
            }
        }
        if (snapshot.getExtraFiles() != null) {
            for (FileEntry file : snapshot.getExtraFiles()) {
                String path = RestorePaths.validate(file.getFilename());
                all.add(RestoreEntry.file(path, file.getSourceHash(), file.getSizeUncompressed(),
                        file.getSourceMetadata()));
                blobPaths.put(path, file.getBlobPath());
            }
        }
        this.entries = List.copyOf(all);
    }

    @Override
    public List<RestoreEntry> entries() {
        return entries;
    }

    @Override
    public InputStream openFile(RestoreEntry entry) throws IOException {
        return blobs.openFile(blobPaths.get(entry.path()));
    }

    @Override
    public void readRegions(List<RestoreEntry> regions, RegionConsumer consumer, int threads) throws IOException {
        if (regions.isEmpty()) return;
        int workers = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers));
        try {
            List<Future<Void>> futures = new ArrayList<>(regions.size());
            for (RestoreEntry entry : regions) {
                Path blob = blobs.getRootDir().resolve(blobPaths.get(entry.path()));
                futures.add(pool.submit(() -> {
                    consumer.accept(entry, sink -> LrhFormat.read(blob, sink));
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while restoring regions", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IOException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Override
    public void close() {
    }
}
