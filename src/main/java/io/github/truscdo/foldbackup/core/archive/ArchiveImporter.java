package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.ProgressEvent;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Archive importer: archives → online system (disaster recovery
 * after index.db / BlobStore loss).
 * <p>
 * Archives are scanned once and processed in epoch-ascending order, so every
 * differential archive's baseline snapshot is already online when its turn comes.
 * The differential baseline is the online snapshot, never the full archive: the full
 * archive's objects already landed in the blob store byte for byte, so reading it again
 * would only be a second read of the same bytes.
 * <p>
 * Region and file content identities and source metadata are preserved. Objects are
 * imported once into the content-addressed blob store; an object whose blob already
 * exists is reused without being read or verified.
 */
public class ArchiveImporter {

    private static final LogUtil LOG = LogUtil.getLogger(ArchiveImporter.class);

    private final Path archiveRoot;
    private final Path backupRoot;
    private final int threads;
    private final java.util.function.Consumer<ArchiveTasks.Metrics> metrics;

    public ArchiveImporter(Path archiveRoot, Path backupRoot) {
        this(archiveRoot, backupRoot, 0);
    }

    /**
     * Explicit worker cap for callers/benchmarks; zero selects the memory-bounded
     * default.
     */
    public ArchiveImporter(Path archiveRoot, Path backupRoot, int threads) {
        this(archiveRoot, backupRoot, threads, null);
    }

    ArchiveImporter(Path archiveRoot, Path backupRoot, int threads, java.util.function.Consumer<ArchiveTasks.Metrics> metrics) {
        if (threads < 0)
            throw new IllegalArgumentException("Negative worker count");
        this.archiveRoot = archiveRoot;
        this.backupRoot = backupRoot;
        this.threads = threads;
        this.metrics = metrics;
    }

    /**
     * Import all archives (full + diff) in epoch-ascending order so full
     * baselines are available before dependent differential archives.
     *
     * @return number of imported snapshots
     */
    public int importAll() throws IOException {
        return importAll(null);
    }

    /** Import all archives with the common structured progress protocol. */
    public int importAll(ProgressListener listener) throws IOException {
        var progress = new ProgressTracker(listener);
        if (!Files.isDirectory(archiveRoot)) {
            throw new IOException("Archive root not found: " + archiveRoot);
        }
        progress.stage("Scanning archives", 1);
        ArchiveLibrary library = ArchiveLibrary.scan(archiveRoot);
        for (String warning : library.warnings()) {
            LOG.warn("{}", warning);
        }
        progress.done("archives");

        var snapshots = new SnapshotStore(backupRoot);
        var blobs = new BlobStore(backupRoot, ArchiveDefaults.RESTORE_LEVEL);
        var errors = new ArchiveErrorCollector();
        for (String libraryError : library.errors()) {
            errors.addRaw(libraryError);
        }

        try (SnapshotStore.WriteSession ignored = snapshots.beginWrite()) {
            int imported = 0;
            List<ArchiveLibrary.Entry> entries = library.entries();
            int totalEntries = entries.size();
            progress.stage("Importing snapshots", totalEntries);
            for (int i = 0; i < totalEntries; i++) {
                ArchiveLibrary.Entry entry = entries.get(i);
                ProgressListener subListener = ProgressTracker.prefixed(
                        listener, "[" + (i + 1) + "/" + totalEntries + "] ");
                try {
                    String id;
                    if (entry.meta().isFull()) {
                        id = importFull(entry, snapshots, blobs, subListener);
                    } else if (entry.meta().isDiff()) {
                        id = importDiff(entry, snapshots, blobs, subListener);
                    } else {
                        throw new IOException("unsupported archive type: " + entry.meta().type);
                    }
                    if (id != null) imported++;
                } catch (IOException | RuntimeException e) {
                    errors.add(entry.fileName(), e.getMessage());
                }
            }
            if (!errors.isEmpty()) {
                throw new IOException("Import completed with " + imported + " snapshot(s) imported and "
                        + errors.size() + " archive error(s):\n - " + String.join("\n - ", errors.list()));
            }
            if (listener != null && totalEntries > 0) {
                listener.onProgress(new ProgressEvent("Importing snapshots", totalEntries, totalEntries, ""));
            }
            return imported;
        }
    }

    @FunctionalInterface
    private interface PlanInputsOpener {
        PlanInputs open(ArchiveSnapshot meta) throws IOException;
    }

    private record PlanInputs(Baseline baseline, TarIndex diff) implements AutoCloseable {
        @Override public void close() throws IOException { baseline.close(); }
    }

    /**
     * Import one full archive. The archive itself is the baseline, and its objects are
     * copied into the blob store unless the blob already exists.
     *
     * @return imported snapshot id, or null if the identical snapshot already exists
     */
    private String importFull(ArchiveLibrary.Entry entry, SnapshotStore snapshots, BlobStore blobs,
            ProgressListener listener) throws IOException {
        return importOne(entry, snapshots, blobs, listener, false,
                meta -> new PlanInputs(new ArchiveBaseline(entry.tar()), null));
    }

    /**
     * Import one diff archive against its online baseline snapshot. The full archive is
     * never opened: unchanged Regions and files are reused from the blob store, changed
     * ones are rebuilt from the diff payloads (and, for split Regions, the baseline blob).
     *
     * @return imported snapshot id, or null if the identical snapshot already exists
     */
    private String importDiff(ArchiveLibrary.Entry entry, SnapshotStore snapshots, BlobStore blobs,
            ProgressListener listener) throws IOException {
        return importOne(entry, snapshots, blobs, listener, true, meta -> {
            SnapshotManifest baseline = snapshots.loadSnapshot(meta.baseEpoch);
            if (baseline == null) {
                String expected = ArchiveFormatUtil.expectedFullName(meta.baseEpoch);
                throw new IOException("required baseline snapshot is not available in the online store: "
                        + SnapshotStore.generateSnapshotId(meta.baseEpoch) + " (import " + expected + " first)");
            }
            TarIndex diff = TarIndex.read(entry.tar());
            return new PlanInputs(new SnapshotBaseline(baseline, blobs), diff);
        });
    }

    /** 共用导入守卫与恢复执行骨架：惰性构建基线以避免已跳过归档的额外 I/O。 */
    private String importOne(ArchiveLibrary.Entry entry, SnapshotStore snapshots, BlobStore blobs,
            ProgressListener listener, boolean merged, PlanInputsOpener opener) throws IOException {
        ArchiveSnapshot meta = entry.meta();
        var progress = new ProgressTracker(listener);
        if (alreadyImported(entry, snapshots, progress)) return null;
        try (var inputs = opener.open(meta);
                var plan = ArchiveRestorePlan.open(inputs.baseline(), meta, inputs.diff())) {
            return importPlan(plan, meta, merged, snapshots, blobs, progress);
        }
    }

    /**
     * Skips an archive whose snapshot is already online and identical. Comparison uses the
     * snapshot's content identity and source metadata; {@code tags} are carried but never
     * decide a conflict.
     *
     * @return true when the archive can be skipped
     */
    private boolean alreadyImported(ArchiveLibrary.Entry entry, SnapshotStore snapshots, ProgressTracker progress)
            throws IOException {
        ArchiveSnapshot meta = entry.meta();
        SnapshotManifest existing = snapshots.loadSnapshot(meta.epoch);
        if (existing == null) return false;
        String id = SnapshotStore.generateSnapshotId(meta.epoch);
        if (!sameTarget(existing, meta)) {
            throw new IOException("snapshot " + id + " already exists with different content"
                    + " (archive: " + entry.fileName() + ")");
        }
        LOG.info("Skipped {} (identical snapshot already exists)", id);
        progress.stage("Committing snapshot", 1);
        progress.done(id);
        return true;
    }

    private String importPlan(ArchiveRestorePlan plan, ArchiveSnapshot meta, boolean merged,
            SnapshotStore snapshots, BlobStore blobs, ProgressTracker progress) throws IOException {
        progress.stage("Importing regions", plan.regions.size());
        List<RegionEntry> regions = merged
                ? importMergedRegions(plan, blobs, progress)
                : importRegionObjects(plan, blobs, progress);
        List<FileEntry> files = importFiles(plan, blobs, progress);
        progress.stage("Committing snapshot", 1);
        SnapshotManifest manifest = buildManifest(meta, regions, files);
        snapshots.saveSnapshot(manifest);
        progress.done(manifest.getSnapshotId());
        LOG.info("Imported {} (source: {}, epoch: {}; {} regions, {} files)",
                manifest.getSnapshotId(), meta.sourceSnapshotId, meta.epoch, regions.size(), files.size());
        return manifest.getSnapshotId();
    }

    /** Whole-archive Region objects: reuse the blob when present, otherwise copy and verify. */
    private List<RegionEntry> importRegionObjects(ArchiveRestorePlan plan, BlobStore blobs, ProgressTracker progress)
            throws IOException {
        if (!plan.regions.isEmpty()) {
            progress.current(plan.regions.getFirst().path());
        }
        return ArchiveTasks.run(plan.regions.size(),
                ArchiveTasks.decompressQuota(plan.regions.size(), plan.maxSourceFrameLevel(), threads), 2,
                index -> importRegionObject(plan.regions.get(index), blobs),
                index -> progress.done(plan.regions.get(index).path()), metrics);
    }

    /** Diff import: only Regions whose target blob is absent are merged. */
    private List<RegionEntry> importMergedRegions(ArchiveRestorePlan plan, BlobStore blobs, ProgressTracker progress)
            throws IOException {
        RegionEntry[] imported = new RegionEntry[plan.regions.size()];
        Set<Integer> missing = new LinkedHashSet<>();
        for (int i = 0; i < plan.regions.size(); i++) {
            var region = plan.regions.get(i);
            if (blobs.exists(region.contentHash(), "lrh")) {
                imported[i] = toExistingRegionEntry(region, blobs);
            } else {
                missing.add(i);
            }
        }
        progress.stage("Merging missing regions", missing.size());
        if (!missing.isEmpty()) {
            progress.current(plan.regions.get(missing.iterator().next()).path());
        }
        List<RegionEntry> processed = plan.runRegions(
                ArchiveTasks.TaskWork.recompress(ArchiveDefaults.RESTORE_LEVEL), threads, metrics,
                missing::contains, index -> progress.done(plan.regions.get(index).path()), (index, source) -> {
                    var region = plan.regions.get(index);
                    try (var session = blobs.newWriteSession()) {
                        var summary = LrhFormat.write(session.channel(), source, ArchiveDefaults.RESTORE_LEVEL);
                        String actualHash = HashUtil.bytesToHex(summary.hash());
                        ArchiveObjects.verifyMergedRegionHash(region, actualHash);
                        var commit = session.commit(actualHash, "lrh");
                        return toRegionEntry(region, actualHash, commit.path(), commit.size());
                    }
                });
        for (int i : missing) {
            imported[i] = processed.get(i);
            if (imported[i] == null) throw new IOException("Missing imported Region: " + plan.regions.get(i).path());
        }
        return List.of(imported);
    }

    private RegionEntry importRegionObject(ArchiveRestorePlan.Region region, BlobStore blobs) throws IOException {
        if (blobs.exists(region.contentHash(), "lrh")) {
            // Read-time verification: an object that is not read is not verified.
            return toExistingRegionEntry(region, blobs);
        }
        var object = region.base();
        if (object == null) throw new IOException("Missing Region object: " + region.path());
        // The stored .lrh bytes are copied verbatim while the decoder verifies the content
        // identity, so the blob stays byte-identical to the archive object.
        try (var session = blobs.newWriteSession(); InputStream input = object.raw().open()) {
            OutputStream encoded = session.output();
            var summary = LrhFormat.read(tee(input, encoded), object.compressedSize(), new RegionStream.Accumulator());
            String actualHash = HashUtil.bytesToHex(summary.hash());
            if (!region.contentHash().equals(actualHash))
                throw new IOException("Region object identity mismatch: " + region.path());
            var commit = session.commit(actualHash, "lrh");
            return toRegionEntry(region, actualHash, commit.path(), commit.size());
        }
    }

    private static RegionEntry toExistingRegionEntry(ArchiveRestorePlan.Region region, BlobStore blobs)
            throws IOException {
        String path = blobs.getRelativePath(region.contentHash(), "lrh");
        return toRegionEntry(region, region.contentHash(), path, blobs.size(path));
    }

    private static RegionEntry toRegionEntry(ArchiveRestorePlan.Region region, String hash, String blobPath,
            long compressedSize) {
        return new RegionEntry(region.path(), hash, blobPath, compressedSize,
                region.sizeUncompressed(), region.sourceMetadata());
    }

    /** Regular files: reuse the blob when present, otherwise copy and verify in one pass. */
    private List<FileEntry> importFiles(ArchiveRestorePlan plan, BlobStore blobs, ProgressTracker progress)
            throws IOException {
        List<FileEntry> files = new ArrayList<>();
        progress.stage("Importing files", plan.files().size());
        Map<String, BlobStore.Commit> stored = new HashMap<>();
        for (var file : plan.files().values()) {
            progress.current(file.path());
            BlobStore.Commit commit = stored.get(file.sourceHash());
            if (commit == null) {
                String path = blobs.getRelativePath(file.sourceHash(), "zst");
                commit = blobs.existsPath(path)
                        ? new BlobStore.Commit(path, blobs.size(path), false)
                        : copyFileObject(file, blobs);
                stored.put(file.sourceHash(), commit);
            }
            files.add(new FileEntry(file.path(), file.sourceHash(), commit.path(), commit.size(),
                    file.sizeUncompressed(), file.sourceMetadata()));
            progress.done(file.path());
        }
        return files;
    }

    /**
     * Copies the compressed object while decoding it, so the identity check and the copy
     * cost one read instead of two. The decoded bytes are hashed, the compressed bytes are
     * what lands in the blob store.
     */
    private BlobStore.Commit copyFileObject(ArchiveRestorePlan.FileRef file, BlobStore blobs) throws IOException {
        try (var session = blobs.newWriteSession()) {
            OutputStream encoded = session.output();
            try (InputStream raw = file.source().open();
                    InputStream decoded = new com.github.luben.zstd.ZstdInputStream(tee(raw, encoded))) {
                FileContentIdentity.Summary summary = FileContentIdentity.inspect(decoded);
                if (!summary.matches(file.sizeUncompressed(), file.sourceHash()))
                    throw new IOException("File object identity mismatch: " + file.path());
            }
            return session.commit(file.sourceHash(), "zst");
        }
    }

    /** Wraps {@code input} so that every byte read is copied to {@code sink} as well. */
    private static InputStream tee(InputStream input, OutputStream sink) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0) sink.write(value);
                return value;
            }

            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                int count = in.read(bytes, off, len);
                if (count > 0) sink.write(bytes, off, count);
                return count;
            }
        };
    }

    /**
     * Compares the target state declared by an archive with an existing online snapshot.
     * Region and file content identity, uncompressed size and source metadata decide;
     * {@code tags} do not.
     */
    private static boolean sameTarget(SnapshotManifest existing, ArchiveSnapshot meta) {
        if (existing.getEpochSeconds() != meta.epoch
                || !Objects.equals(text(existing.getWorldName()), text(meta.world))
                || !Objects.equals(text(existing.getMinecraftVersion()), text(meta.minecraftVersion))
                || existing.getWorldVersion() != meta.worldVersion) return false;
        List<RegionEntry> existingRegions = existing.getRegions() == null ? List.of() : existing.getRegions();
        if (existingRegions.size() != meta.regions.size()) return false;
        Map<String, RegionEntry> regionsByPath = new HashMap<>();
        for (RegionEntry region : existingRegions) regionsByPath.put(region.getFilename(), region);
        for (var info : meta.regions) {
            RegionEntry found = regionsByPath.get(info.filename);
            if (found == null || !Objects.equals(found.getContentHash(), info.contentHash)
                    || found.getSizeUncompressed() != info.sizeUncompressed
                    || !Objects.equals(found.getSourceMetadata(), ArchiveObjects.metadata(info.sourceMetadata))) return false;
        }
        List<FileEntry> existingFiles = existing.getExtraFiles() == null ? List.of() : existing.getExtraFiles();
        if (existingFiles.size() != meta.files.size()) return false;
        Map<String, FileEntry> filesByPath = new HashMap<>();
        for (FileEntry file : existingFiles) filesByPath.put(file.getFilename(), file);
        for (var info : meta.files) {
            FileEntry found = filesByPath.get(info.filename);
            if (found == null || !Objects.equals(found.getSourceHash(), info.sourceHash)
                    || found.getSizeUncompressed() != info.sizeUncompressed
                    || !Objects.equals(found.getSourceMetadata(), ArchiveObjects.metadata(info.sourceMetadata))) return false;
        }
        return true;
    }

    /** 归档的 {@code snapshot.bin} 用空串表示“无值”，索引读回时是 {@code null}，两者等价。 */
    private static String text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Build an online manifest from archive metadata + imported entries. */
    private static SnapshotManifest buildManifest(ArchiveSnapshot meta,
            List<RegionEntry> regions, List<FileEntry> files) {
        // ArchiveSnapshot.epoch and SnapshotManifest epoch are both Unix epoch
        // seconds; no unit conversion is needed at this boundary.
        SnapshotManifest manifest = new SnapshotManifest(SnapshotStore.generateSnapshotId(meta.epoch),
                meta.epoch, meta.world);
        manifest.setMinecraftVersion(meta.minecraftVersion);
        manifest.setWorldVersion(meta.worldVersion);
        if (meta.tags != null) {
            manifest.setTags(meta.tags);
        }
        manifest.setRegions(regions);
        manifest.setExtraFiles(files);
        manifest.setSummary(SnapshotManifest.Summary.summarize(regions, files));
        return manifest;
    }
}
