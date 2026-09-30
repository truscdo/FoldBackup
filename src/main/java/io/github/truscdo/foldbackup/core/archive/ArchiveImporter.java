package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Archive importer: archives → online system (disaster recovery
 * after index.db / BlobStore loss).
 * <p>
 * Imports <b>all</b> archive tars (full + diff) in epoch-ascending order so each
 * differential archive's full baseline is available before it is applied.
 * <p>
 * Full and differential archives preserve the region and file content
 * identities and source metadata. Referenced objects are imported once into
 * the online content-addressed blob store.
 */
public class ArchiveImporter {

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
        // Enumerate all tars (full + diff), read snapshot.bin, sort by epoch
        List<TarInfo> tars = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (var stream = Files.list(archiveRoot)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!p.getFileName().toString().endsWith(".tar")) {
                    continue;
                }
                try {
                    ArchiveSnapshot meta = readSnapshot(p);
                    if (meta == null) errors.add(p.getFileName() + ": snapshot.bin is missing");
                    else tars.add(new TarInfo(p, meta));
                } catch (IOException | RuntimeException e) {
                    errors.add(p.getFileName() + ": " + e.getMessage());
                }
            }
        }
        tars.sort(Comparator.comparingLong(t -> t.meta.epoch));
        progress.done("archives");

        var snapshotManager = new SnapshotStore(backupRoot);
        var blobStore = new BlobStore(backupRoot, 6);

        try (SnapshotStore.WriteSession ignored = snapshotManager.beginWrite()) {
            int imported = 0;
            progress.stage("Importing snapshots", tars.size());
            for (TarInfo t : tars) {
                String id;
                String item = t.meta.sourceSnapshotId != null
                        ? t.meta.sourceSnapshotId
                        : t.tar.getFileName().toString();
                try {
                    if (t.meta.isFull()) {
                        id = importFull(t.tar, snapshotManager, blobStore, listener);
                    } else if (t.meta.isDiff()) {
                        id = importDiff(t.tar, t.meta, snapshotManager, blobStore, listener);
                    } else {
                        throw new IOException("unsupported archive type: " + t.meta.type);
                    }
                    if (id != null) imported++;
                } catch (IOException | RuntimeException e) {
                    errors.add(t.tar.getFileName() + ": " + e.getMessage());
                } finally {
                    progress.done(item);
                }
            }
            if (!errors.isEmpty()) {
                throw new IOException("Import completed with " + imported + " snapshot(s) imported and "
                        + errors.size() + " archive error(s):\n - " + String.join("\n - ", errors));
            }
            return imported;
        }
    }

    /** A archive tar plus its parsed snapshot.bin. */
    private static final class TarInfo {
        final Path tar;
        final ArchiveSnapshot meta;

        TarInfo(Path tar, ArchiveSnapshot meta) {
            this.tar = tar;
            this.meta = meta;
        }
    }

    /**
     * Import one full archive tar. Skips if the snapshot epoch already exists.
     *
     * @return imported snapshot id, or null if skipped
     */
    private String importFull(Path tar, SnapshotStore snapshotManager, BlobStore blobStore,
            ProgressListener listener) throws IOException {
        ArchiveSnapshot meta = readSnapshot(tar);
        if (meta == null || !"full".equals(meta.type)) {
            System.out.println("Skip " + tar.getFileName() + " (not a full archive)");
            return null;
        }
        try (var plan = ArchiveRestorePlan.open(tar, null)) {
            return importPlan(plan, false, snapshotManager, blobStore,
                    new ProgressTracker(listener));
        }
    }

    /**
     * Import one diff backup tar: reuse unchanged baseline blobs and merge changed
     * regions from the full and diff archives.
     * Skips if the snapshot epoch already exists, the baseline full archive is
     * missing, or the baseline full has not been imported yet.
     *
     * @return imported snapshot id, or null if skipped
     */
    private String importDiff(Path diffTar, ArchiveSnapshot meta, SnapshotStore snapshotManager,
            BlobStore blobStore, ProgressListener listener) throws IOException {
        // Baseline full archive must exist in the archive root and be imported first
        Path fullTar;
        try {
            fullTar = findFullTar(meta.baseEpoch);
        } catch (IOException e) {
            String expected = ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(meta.baseEpoch));
            throw new IOException("required baseline full archive " + expected
                    + " is missing for epoch " + meta.baseEpoch, e);
        }
        if (!Files.isRegularFile(fullTar)) {
            throw new IOException("required baseline full archive not found: " + fullTar.getFileName());
        }
        ArchiveSnapshot baseMeta = readSnapshot(fullTar);
        SnapshotManifest baseSnapshot = baseMeta == null ? null
                : snapshotManager.loadSnapshot(meta.baseEpoch);
        if (baseSnapshot == null) {
            throw new IOException("required baseline snapshot is not available in the online store: "
                    + SnapshotStore.generateSnapshotId(meta.baseEpoch));
        }
        ArchiveVerifier.Result verification = new ArchiveVerifier().verifyArchive(archiveRoot, diffTar);
        if (!verification.ok())
            throw new IOException("archive or required baseline failed verification: "
                    + String.join("; ", verification.errors));
        try (var plan = ArchiveRestorePlan.open(fullTar, diffTar)) {
            return importPlan(plan, true, snapshotManager, blobStore,
                    new ProgressTracker(listener));
        }
    }

    private String importPlan(ArchiveRestorePlan plan, boolean merged, SnapshotStore snapshots,
            BlobStore blobs, ProgressTracker progress)
            throws IOException {
        progress.stage("Importing regions", plan.regions.size());
        List<RegionEntry> regions;
        if (!merged) {
            regions = ArchiveTasks.run(plan.regions.size(),
                    ArchiveTasks.workers(plan.regions.size(), 6, threads), 2,
                    index -> importRegionObject(plan.regions.get(index), blobs),
                    index -> progress.done(plan.regions.get(index).path()), metrics);
        } else {
            RegionEntry[] imported = new RegionEntry[plan.regions.size()];
            Set<Integer> missing = new LinkedHashSet<>();
            for (int i = 0; i < plan.regions.size(); i++) {
                var region = plan.regions.get(i);
                if (blobs.exists(region.contentHash(), "lrh")) {
                    String path = blobs.getRelativePath(region.contentHash(), "lrh");
                    imported[i] = new RegionEntry(region.path(), region.contentHash(), path, blobs.size(path),
                            region.sizeUncompressed(), region.sourceMetadata());
                } else missing.add(i);
            }
            progress.stage("Merging missing regions", missing.size());
            List<RegionEntry> processed = plan.runRegions(ArchiveDefaults.RESTORE_LEVEL, threads, metrics,
                    missing::contains, index -> progress.done(plan.regions.get(index).path()), (index, source) -> {
                        var region = plan.regions.get(index);
                        try (var session = blobs.newWriteSession()) {
                            var summary = LrhFormat.write(session.channel(), source, ArchiveDefaults.RESTORE_LEVEL);
                            String actualHash = HashUtil.bytesToHex(summary.hash());
                            if (!region.contentHash().equals(actualHash))
                                throw new IOException("Merged Region identity mismatch: " + region.path());
                            var commit = session.commit(actualHash, "lrh");
                            return new RegionEntry(region.path(), actualHash, commit.path(), commit.size(),
                                    region.sizeUncompressed(), region.sourceMetadata());
                        }
                    });
            for (int i : missing) {
                imported[i] = processed.get(i);
                if (imported[i] == null) throw new IOException("Missing imported Region: " + plan.regions.get(i).path());
            }
            regions = List.of(imported);
        }
        List<FileEntry> files = importFiles(plan, blobs, progress);
        progress.stage("Committing snapshot", 1);
        SnapshotManifest manifest = buildManifest(plan.meta, regions, files);
        SnapshotManifest existing = snapshots.loadSnapshot(plan.meta.epoch);
        if (existing != null) {
            if (!sameSnapshot(existing, manifest))
                throw new IOException("Snapshot ID conflict: existing content differs for "
                        + manifest.getSnapshotId());
            System.out.println("Skipped " + manifest.getSnapshotId() + " (identical snapshot already exists)");
            progress.done(manifest.getSnapshotId());
            return null;
        }
        snapshots.saveSnapshot(manifest);
        progress.done(manifest.getSnapshotId());
        System.out.println("Imported " + manifest.getSnapshotId() + " (source: "
                + plan.meta.sourceSnapshotId + ", epoch: " + plan.meta.epoch + "; " + regions.size()
                + " regions, " + files.size() + " files)");
        return manifest.getSnapshotId();
    }

    private static boolean sameSnapshot(SnapshotManifest left, SnapshotManifest right) {
        if (!java.util.Objects.equals(left.getSnapshotId(), right.getSnapshotId())
                || left.getEpochSeconds() != right.getEpochSeconds()
                || !java.util.Objects.equals(left.getWorldName(), right.getWorldName())
                || !java.util.Objects.equals(left.getMinecraftVersion(), right.getMinecraftVersion())
                || left.getWorldVersion() != right.getWorldVersion()
                || !java.util.Objects.equals(left.getTags(), right.getTags())) return false;
        Map<String, RegionEntry> leftRegions = new HashMap<>();
        if (left.getRegions() != null) for (RegionEntry region : left.getRegions())
            leftRegions.put(region.getFilename(), region);
        if (right.getRegions() == null || leftRegions.size() != right.getRegions().size()) return false;
        for (RegionEntry region : right.getRegions()) {
            RegionEntry found = leftRegions.get(region.getFilename());
            if (found == null || !java.util.Objects.equals(found.getContentHash(), region.getContentHash())
                    || found.getSizeUncompressed() != region.getSizeUncompressed()
                    || !java.util.Objects.equals(found.getSourceMetadata(), region.getSourceMetadata())) return false;
        }
        Map<String, FileEntry> leftFiles = new HashMap<>();
        if (left.getExtraFiles() != null) for (FileEntry file : left.getExtraFiles())
            leftFiles.put(file.getFilename(), file);
        if (right.getExtraFiles() == null || leftFiles.size() != right.getExtraFiles().size()) return false;
        for (FileEntry file : right.getExtraFiles()) {
            FileEntry found = leftFiles.get(file.getFilename());
            if (found == null || !java.util.Objects.equals(found.getSourceHash(), file.getSourceHash())
                    || found.getSizeUncompressed() != file.getSizeUncompressed()
                    || !java.util.Objects.equals(found.getSourceMetadata(), file.getSourceMetadata())) return false;
        }
        return true;
    }

    private RegionEntry importRegionObject(ArchiveRestorePlan.Region region, BlobStore blobs) throws IOException {
        TarIndex.Entry object = region.base();
        if (object == null) throw new IOException("Missing Region object: " + region.path());
        String blobPath = blobs.getRelativePath(region.contentHash(), "lrh");
        if (blobs.exists(region.contentHash(), "lrh")) {
            RegionStream.Summary summary;
            try (InputStream input = object.open()) {
                summary = LrhFormat.read(input, object.size(), new RegionStream.Accumulator());
            }
            if (!region.contentHash().equals(HashUtil.bytesToHex(summary.hash())))
                throw new IOException("Region object identity mismatch: " + region.path());
            return new RegionEntry(region.path(), region.contentHash(), blobPath, blobs.size(blobPath),
                    region.sizeUncompressed(), region.sourceMetadata());
        }
        try (var session = blobs.newWriteSession(); InputStream input = object.open()) {
            InputStream tee = new java.io.FilterInputStream(input) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) session.output().write(value);
                    return value;
                }
                @Override public int read(byte[] bytes, int off, int len) throws IOException {
                    int count = in.read(bytes, off, len);
                    if (count > 0) session.output().write(bytes, off, count);
                    return count;
                }
            };
            var summary = LrhFormat.read(tee, object.size(), new RegionStream.Accumulator());
            String actualHash = HashUtil.bytesToHex(summary.hash());
            if (!region.contentHash().equals(actualHash))
                throw new IOException("Region object identity mismatch: " + region.path());
            var commit = session.commit(actualHash, "lrh");
            return new RegionEntry(region.path(), actualHash, commit.path(), commit.size(),
                    region.sizeUncompressed(), region.sourceMetadata());
        }
    }

    private List<FileEntry> importFiles(ArchiveRestorePlan plan, BlobStore blobs, ProgressTracker progress)
            throws IOException {
        List<FileEntry> files = new ArrayList<>();
        progress.stage("Importing files", plan.files().size());
        Map<String, BlobStore.Commit> stored = new HashMap<>();
        for (var file : plan.files().values()) {
            progress.current(file.path());
            BlobStore.Commit commit = stored.get(file.sourceHash());
            if (commit == null) {
                verifyFileObject(file);
                String path = blobs.getRelativePath(file.sourceHash(), "zst");
                if (blobs.exists(file.sourceHash(), "zst")) {
                    commit = new BlobStore.Commit(path, blobs.size(path), false);
                } else {
                    try (var session = blobs.newWriteSession(); InputStream input = file.object().open()) {
                        input.transferTo(session.output());
                        commit = session.commit(file.sourceHash(), "zst");
                    }
                }
                stored.put(file.sourceHash(), commit);
            }
            files.add(new FileEntry(file.path(), file.sourceHash(), commit.path(), commit.size(),
                    file.sizeUncompressed(), file.sourceMetadata()));
            progress.done(file.path());
        }
        return files;
    }

    private void verifyFileObject(ArchiveRestorePlan.FileRef file) throws IOException {
        FileContentIdentity.Summary summary;
        try (InputStream raw = file.object().open();
                InputStream input = new com.github.luben.zstd.ZstdInputStream(raw)) {
            summary = FileContentIdentity.inspect(input);
        }
        if (!summary.matches(file.sizeUncompressed(), file.sourceHash()))
            throw new IOException("File object identity mismatch: " + file.path());
    }

    private Path findFullTar(long epoch) throws IOException {
        return ArchiveTarScanner.fullAtEpoch(ArchiveTarScanner.scan(archiveRoot), epoch);
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
        manifest.setSummary(buildSummary(regions, files));
        return manifest;
    }

    private static SnapshotManifest.Summary buildSummary(List<RegionEntry> regions, List<FileEntry> files) {
        SnapshotManifest.Summary summary = new SnapshotManifest.Summary();
        summary.setRegionsTotal(regions.size());
        summary.setExtraFilesTotal(files.size());
        long comp = 0;
        long uncomp = 0;
        for (RegionEntry re : regions) {
            comp += re.getSizeCompressed();
            uncomp += re.getSizeUncompressed();
        }
        for (FileEntry fe : files) {
            comp += fe.getSizeCompressed();
            uncomp += fe.getSizeUncompressed();
        }
        summary.setTotalCompressedBytes(comp);
        summary.setTotalUncompressedBytes(uncomp);
        return summary;
    }

    // ── helpers ──

    private ArchiveSnapshot readSnapshot(Path tar) throws IOException {
        try (InputStream fis = Files.newInputStream(tar);
                TarReader reader = new TarReader(fis)) {
            TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) {
                if (e.name.equals("snapshot.bin")) {
                    byte[] data = reader.readEntryData();
                    return ArchiveSnapshotCodec.decode(data);
                }
                reader.readEntryData();
            }
        }
        return null;
    }

}
