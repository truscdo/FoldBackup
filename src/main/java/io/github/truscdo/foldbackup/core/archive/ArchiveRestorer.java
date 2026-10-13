package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.McaStreamWriter;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Archive restorer: {@code full.tar} ± {@code diff.tar} → standard
 * Anvil world directory. Zero dependency on the online system (index.db /
 * BlobStore) — only reads the archive directory.
 * <p>
 * Restore = full archive + single diff (no chain). Baseline assertion:
 * {@code diff.baseEpoch} must equal {@code full.epoch}, otherwise restore
 * is refused before writing anything.
 */
public class ArchiveRestorer {

    private final int compressionLevel;
    private final int threads;
    private final java.util.function.Consumer<ArchiveTasks.Metrics> metrics;

    public ArchiveRestorer(int compressionLevel) {
        this(compressionLevel, 0);
    }

    /** Explicit worker cap; zero selects the memory-bounded default. */
    public ArchiveRestorer(int compressionLevel, int threads) {
        this(compressionLevel, threads, null);
    }

    ArchiveRestorer(int compressionLevel, int threads, java.util.function.Consumer<ArchiveTasks.Metrics> metrics) {
        if (threads < 0) throw new IllegalArgumentException("Negative worker count");
        this.compressionLevel = compressionLevel;
        this.threads = threads;
        this.metrics = metrics;
    }

    /**
     * Restore a world from the archive directory.
     *
     * @param archiveRoot  archive directory
     * @param baseEpochSeconds full archive identity, or null for the latest full archive
     * @param diffEpochSeconds diff archive identity, or null for full-only
     * @param outputDir target world directory (created if absent)
     * @return the output directory
     */
    public Path restore(Path archiveRoot, Long baseEpochSeconds, Long diffEpochSeconds, Path outputDir) throws IOException {
        return restore(archiveRoot, baseEpochSeconds, diffEpochSeconds, outputDir, null);
    }

    /** Restore with the common structured progress protocol. */
    public Path restore(Path archiveRoot, Long baseEpochSeconds, Long diffEpochSeconds, Path outputDir,
            ProgressListener listener) throws IOException {
        if (!Files.isDirectory(archiveRoot)) throw new IOException("Archive root not found: " + archiveRoot);
        Path fullTar = locateFullTar(archiveRoot, baseEpochSeconds);
        Path diffTar = diffEpochSeconds == null ? null
                : locateDiffTar(archiveRoot, baseEpochSeconds, diffEpochSeconds);
        return restoreArchives(fullTar, diffTar, outputDir, listener);
    }

    /** Restore the snapshot represented by a selected archive file. */
    public Path restoreArchive(Path archiveRoot, Path archiveFile, Path outputDir,
            ProgressListener listener) throws IOException {
        if (!Files.isDirectory(archiveRoot)) throw new IOException("Archive root not found: " + archiveRoot);
        if (!Files.isRegularFile(archiveFile)) throw new IOException("Archive file not found: " + archiveFile);
        ArchiveSnapshot meta = ArchiveFormatUtil.readSnapshot(archiveFile);
        if (meta == null) throw new IOException("Cannot read snapshot metadata from archive: " + archiveFile);

        Path fullTar;
        Path diffTar = null;
        if (meta.isFull()) {
            fullTar = archiveFile;
        } else if (meta.isDiff()) {
            try {
                fullTar = ArchiveTarScanner.fullAtEpoch(ArchiveTarScanner.scan(archiveRoot), meta.baseEpoch);
            } catch (IOException e) {
                String expected = ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(meta.baseEpoch));
                throw new IOException("Required baseline archive " + expected + " is missing", e);
            }
            diffTar = archiveFile;
        } else {
            throw new IOException("Unknown archive type in " + archiveFile + ": " + meta.type);
        }
        return restoreArchives(fullTar, diffTar, outputDir, listener);
    }

    private Path restoreArchives(Path fullTar, Path diffTar, Path outputDir,
            ProgressListener listener) throws IOException {
        var progress = new ProgressTracker(listener);
        progress.stage("Reading archive", diffTar == null ? 1 : 2);
        progress.done(fullTar.getFileName().toString());
        if (diffTar != null) progress.done(diffTar.getFileName().toString());
        try (var plan = ArchiveRestorePlan.open(fullTar, diffTar);
                var staged = new ArchiveTemporaryDirectory()) {
            progress.stage("Restoring files", plan.files().size());
            for (var file : plan.files().values()) try (InputStream raw = file.object().open();
                    InputStream in = new com.github.luben.zstd.ZstdInputStream(raw)) {
                progress.current(file.path());
                WorldRestoreSupport.writeFile(staged.path, file.path(), file.sizeUncompressed(), file.sourceHash(), in);
                progress.done(file.path());
            }

            progress.stage("Restoring regions", plan.regions.size());
            plan.runRegions(ArchiveTasks.TaskWork.decompress(), threads, metrics,
                    index -> progress.done(plan.regions.get(index).path()), (index, source) -> {
                var region = plan.regions.get(index);
                McaStreamWriter.write(staged.path.resolve(region.path()), region.rx(), region.rz(), 0,
                        compressionLevel, WorldRestoreSupport.verifiedSource(region.path(), region.contentHash(), source));
                return Boolean.TRUE;
            });
            if (!plan.deleted.isEmpty()) {
                progress.stage("Removing deleted files", plan.deleted.size());
                for (String path : plan.deleted) {
                    Files.deleteIfExists(staged.path.resolve(path));
                    progress.done(path);
                }
            }

            // Publish only after every LRH/LRHD frame and chunk hash has passed.
            WorldRestoreSupport.publish(staged.path, outputDir, progress);
            if (!plan.deleted.isEmpty()) {
                progress.stage("Removing deleted files", plan.deleted.size());
                for (String path : plan.deleted) {
                    Files.deleteIfExists(outputDir.resolve(path));
                    progress.done(path);
                }
            }
            return outputDir;
        }
    }

    // ── tar location ──

    private Path locateFullTar(Path archiveRoot, Long baseEpochSeconds) throws IOException {
        List<ArchiveTarScanner.ArchiveTarInfo> tars = ArchiveTarScanner.scan(archiveRoot);
        if (baseEpochSeconds != null) return ArchiveTarScanner.fullAtEpoch(tars, baseEpochSeconds);
        List<ArchiveTarScanner.ArchiveTarInfo> fulls = ArchiveTarScanner.fulls(tars);
        if (fulls.isEmpty()) throw new IOException("No full archive found in " + archiveRoot);
        return ArchiveTarScanner.fullAtEpoch(tars, fulls.getFirst().meta().epoch);
    }

    private Path locateDiffTar(Path archiveRoot, Long baseEpochSeconds, long diffEpochSeconds) throws IOException {
        if (baseEpochSeconds == null) {
            throw new IOException("--base is required when --diff is specified");
        }
        return ArchiveTarScanner.diffAtEpoch(ArchiveTarScanner.scan(archiveRoot), baseEpochSeconds, diffEpochSeconds);
    }

}
