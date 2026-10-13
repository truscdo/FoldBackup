package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;
import io.github.truscdo.foldbackup.core.store.EntryMetadata;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import io.github.truscdo.foldbackup.core.format.AnvilFormat;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.format.ZstdStream;
import io.github.truscdo.foldbackup.core.format.McaHasher;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Scans a Minecraft world directory and produces snapshot region/file entries.
 * <p>
 * Traversal and processing are decoupled by {@link BoundedWork}: files are
 * submitted while the tree is visited, the submission window is bounded, and
 * results are collected by this thread in visit order. No path list, full file,
 * full region or compressed buffer is retained.
 * <p>
 * Regions and regular files use source metadata from the latest snapshot for
 * a path fast path, then a path-independent XXH3-128 content identity for blob
 * reuse. Region content identity excludes timestamps through {@link RegionStream}.
 */
public class RegionProcessor {

    private static final LogUtil LOG = LogUtil.getLogger(RegionProcessor.class);

    private static final Pattern REGION_FILE = Pattern.compile("^r\\.-?\\d+\\.-?\\d+\\.mca$");

    private final BlobStore blobStore;
    private final int compressionLevel;
    private final java.util.function.Supplier<MetadataIndex> indexSupplier;
    private final BackupFilter backupFilter;
    private final int regionThreads;
    private final long regionMemoryLimitMB;

    /**
     * @param metadataIndex in-memory index for contentHash dedup lookups;
     *                      may be {@code null} when no content index is available
     */
    public RegionProcessor(BlobStore blobStore, int compressionLevel,
            MetadataIndex metadataIndex) {
        this(blobStore, compressionLevel, metadataIndex, BackupFilter.defaults(), 0, 0);
    }

    /**
     * @param metadataIndex in-memory index for contentHash dedup lookups;
     *                      may be {@code null} when no content index is available
     * @param backupFilter  backup target filter (glob rules); {@code null} falls
     *                      back to {@link BackupFilter#defaults()}
     */
    public RegionProcessor(BlobStore blobStore, int compressionLevel,
            MetadataIndex metadataIndex, BackupFilter backupFilter) {
        this(blobStore, compressionLevel, metadataIndex, backupFilter, 0, 0);
    }

    /**
     * Create a region processor with adaptive resource limits.
     *
     * @param regionThreads       CPU worker count; zero selects automatic mode
     * @param regionMemoryLimitMB in-flight region memory limit; zero selects
     *                            automatic mode
     */
    public RegionProcessor(BlobStore blobStore, int compressionLevel,
            MetadataIndex metadataIndex, BackupFilter backupFilter,
            int regionThreads, long regionMemoryLimitMB) {
        this(() -> metadataIndex, blobStore, compressionLevel, backupFilter,
                regionThreads, regionMemoryLimitMB);
    }

    /**
     * Query the store's current committed index, including after later
     * saves/deletions.
     */
    public RegionProcessor(java.util.function.Supplier<MetadataIndex> indexSupplier,
            BlobStore blobStore, int compressionLevel) {
        this(indexSupplier, blobStore, compressionLevel, BackupFilter.defaults(), 0, 0);
    }

    /** Create a processor backed by a replaceable committed index. */
    public RegionProcessor(java.util.function.Supplier<MetadataIndex> indexSupplier,
            BlobStore blobStore, int compressionLevel, BackupFilter backupFilter,
            int regionThreads, long regionMemoryLimitMB) {
        this.blobStore = blobStore;
        this.compressionLevel = compressionLevel;
        this.indexSupplier = java.util.Objects.requireNonNull(indexSupplier);
        this.backupFilter = backupFilter != null ? backupFilter : BackupFilter.defaults();
        this.regionThreads = Math.max(0, regionThreads);
        this.regionMemoryLimitMB = Math.max(0, regionMemoryLimitMB);
    }

    /**
     * Scan a world directory and produce snapshot entries.
     *
     * @param worldDir      the Minecraft world directory
     * @param latestSnapshot most recently committed snapshot, or {@code null}
     *                       for an initial backup
     * @return scan result with region and file entries
     */
    public ScanResult scanWorld(Path worldDir, SnapshotManifest latestSnapshot,
            Consumer<ScanEvent> progressCallback) throws IOException {
        return scanWorld(worldDir, latestSnapshot, progressCallback, "standalone");
    }

    public ScanResult scanWorld(Path worldDir, SnapshotManifest latestSnapshot,
            Consumer<ScanEvent> progressCallback, String snapshotId) throws IOException {
        if (!Files.isDirectory(worldDir))
            throw new IOException("World directory does not exist: " + worldDir);
        ScanResult result = new ScanResult();
        Map<String, RegionEntry> latestRegions = buildRegionMap(latestSnapshot);
        Map<String, FileEntry> latestFiles = buildFileMap(latestSnapshot);
        DedupStats stats = new DedupStats(snapshotId);
        long memory = BackupResourceBudget.memoryBudgetBytes(regionMemoryLimitMB, Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        int workers = BackupResourceBudget.modRegionQuota(Integer.MAX_VALUE, memory, 0,
                BackupResourceBudget.compressTaskBytes(compressionLevel),
                Runtime.getRuntime().availableProcessors(), regionThreads).workers();
        try (var work = new BoundedWork<ScanResult>(workers, partial -> {
            blobStore.checkBackupWrites();
            for (var entry : partial.getRegions())
                result.addRegion(entry);
            for (var entry : partial.getFiles())
                result.addFile(entry);
            result.getErrors().addAll(partial.getErrors());
            result.changedRegions += partial.changedRegions;
            result.unchangedRegions += partial.unchangedRegions;
            result.changedFiles += partial.changedFiles;
            result.unchangedFiles += partial.unchangedFiles;
            result.newBytes += partial.newBytes;
            if (progressCallback != null)
                progressCallback.accept(new ScanEvent.Completed(partial.path,
                        partial.regionTask, !partial.getErrors().isEmpty()));
        })) {
            Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return backupFilter.skipDirectory(worldDir.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.size() == 0)
                        return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString();
                    String relative = worldDir.relativize(file).toString().replace('\\', '/');
                    if (backupFilter.skipFile(relative) || name.endsWith(".mcc"))
                        return FileVisitResult.CONTINUE;
                    boolean region = REGION_FILE.matcher(name).matches();
                    if (progressCallback != null)
                        progressCallback.accept(region ? new ScanEvent.Region(relative) : new ScanEvent.File(relative));
                    work.submit(() -> {
                        ScanResult partial = new ScanResult();
                        partial.path = relative;
                        partial.regionTask = region;
                        try {
                            blobStore.checkBackupWrites();
                            if (region) {
                                RegionOutcome outcome = processRegionFile(worldDir, file, relative, latestRegions, stats);
                                partial.addRegion(outcome.entry());
                                if (outcome.changed())
                                    partial.changedRegions++;
                                else
                                    partial.unchangedRegions++;
                                partial.newBytes += outcome.newBytes();
                            } else
                                processExtraFile(file, relative, latestFiles, partial);
                        } catch (Exception e) {
                            partial.addError(relative, e);
                            stats.errors.increment();
                            LOG.warn("Failed to process file: " + relative, e);
                        }
                        return partial;
                    });
                    return FileVisitResult.CONTINUE;
                }
            });
            work.finish();
            blobStore.checkBackupWrites();
            result.finalizeResult();
            return result;
        } finally {
            stats.log();
        }
    }

    public BlobStore.WriteScope beginBackupWrites(long indexBytes, long maxBlobBytes) {
        return blobStore.beginBackupWrites(indexBytes, maxBlobBytes);
    }

    public ScanResult scanWorld(Path worldDir, SnapshotManifest latestSnapshot) throws IOException {
        return scanWorld(worldDir, latestSnapshot, null);
    }

    // ──────────────────────────────────────────────
    // Region processing
    // ──────────────────────────────────────────────

    private RegionOutcome processRegionFile(Path worldDir, Path mcaPath, String relPath,
            Map<String, RegionEntry> latestRegions, DedupStats stats) throws IOException {
        SourceMetadata before = regionSourceMetadata(worldDir, mcaPath);
        RegionEntry previous = latestRegions.get(relPath);
        if (previous != null && !previous.getSourceMetadata().isEmpty()
                && previous.getSourceMetadata().equals(before)
                && blobStore.existsPath(previous.getBlobPath())
                && before.equals(regionSourceMetadata(worldDir, mcaPath))) {
            stats.metadataHits.increment();
            return RegionOutcome.success(new RegionEntry(relPath, previous.getContentHash(), previous.getBlobPath(),
                    previous.getSizeCompressed(), previous.getSizeUncompressed(), before), false, 0);
        }
        long sourceBytes = metadataSize(before);
        long start = System.nanoTime();
        RegionStream.Summary checked = RegionStream.inspect(McaStream.source(mcaPath));
        long hashMs = (System.nanoTime() - start) / 1_000_000;
        String checkedHash = HashUtil.bytesToHex(checked.hash());
        String existing = findBlobByContentHash(EntryMetadata.Type.REGION, checkedHash);
        stats.checks.increment();
        stats.hashMs.add(hashMs);
        SourceMetadata afterCheck = regionSourceMetadata(worldDir, mcaPath);
        boolean changedDuringRead = !before.equals(afterCheck);
        if (existing != null) {
            stats.hits.increment();
            if (changedDuringRead)
                LOG.warn("backup_source_changed path={} kind=region action=record_content", quote(relPath));
            return RegionOutcome.success(new RegionEntry(relPath, checkedHash, existing,
                    blobStore.size(existing), sourceBytes, changedDuringRead ? SourceMetadata.empty() : afterCheck), true, 0);
        }
        stats.misses.increment();
        start = System.nanoTime();
        try (var session = blobStore.newWriteSession()) {
            RegionStream.Summary actual = LrhFormat.write(session.channel(), McaStream.source(mcaPath),
                    compressionLevel);
            boolean changed = !checked.same(actual) || changedDuringRead;
            SourceMetadata afterWrite = SourceMetadata.empty();
            try {
                afterWrite = regionSourceMetadata(worldDir, mcaPath);
                changed |= !afterCheck.equals(afterWrite);
            } catch (IOException e) {
                changed = true;
                LOG.warn("Unable to recheck source metadata: " + quote(relPath), e);
            }
            String actualHash = HashUtil.bytesToHex(actual.hash());
            if (changed)
                LOG.warn("backup_source_changed path={} kind=region action=commit_actual", quote(relPath));
            BlobStore.Commit commit = session.commit(actualHash, "lrh");
            long writeMs = (System.nanoTime() - start) / 1_000_000;
            stats.writeMs.add(writeMs);
            return RegionOutcome.success(new RegionEntry(relPath, actualHash, commit.path(),
                    commit.size(), sourceBytes, changed ? SourceMetadata.empty() : afterWrite), true, commit.created() ? commit.size() : 0);
        }
    }

    private static SourceMetadata regionSourceMetadata(Path worldDir, Path path) throws IOException {
        List<SourceMetadata.SourceFile> files = new ArrayList<>();
        Path relativeMca = worldDir.relativize(path);
        files.add(SourceMetadata.SourceFile.of(relativeMca.toString().replace('\\', '/'),
                Files.readAttributes(path, BasicFileAttributes.class)));
        int[] coords = AnvilFormat.parseRegionCoords(path.getFileName().toString());
        for (Path mcc : McaHasher.findMccFilesForRegion(path.getParent(), coords[0], coords[1]))
            files.add(SourceMetadata.SourceFile.of(worldDir.relativize(mcc).toString().replace('\\', '/'),
                    Files.readAttributes(mcc, BasicFileAttributes.class)));
        return new SourceMetadata(files);
    }

    private static long metadataSize(SourceMetadata metadata) {
        return metadata.files().stream().mapToLong(SourceMetadata.SourceFile::size).sum();
    }

    // ──────────────────────────────────────────────
    // Extra file processing
    // ──────────────────────────────────────────────

    private void processExtraFile(Path file, String relPath,
            Map<String, FileEntry> latestFiles,
            ScanResult result) throws IOException {
        SourceMetadata before = SourceMetadata.of(relPath, Files.readAttributes(file, BasicFileAttributes.class));
        FileEntry previous = latestFiles.get(relPath);
        if (previous != null && !previous.getSourceMetadata().isEmpty()
                && previous.getSourceMetadata().equals(before)
                && blobStore.existsPath(previous.getBlobPath())
                && before.equals(SourceMetadata.of(relPath, Files.readAttributes(file, BasicFileAttributes.class)))) {
            result.addFile(new FileEntry(
                    relPath, previous.getSourceHash(), previous.getBlobPath(),
                    previous.getSizeCompressed(), previous.getSizeUncompressed(), before));
            result.unchangedFiles++;
            return;
        }

        var inspected = inspectExtraFile(file);
        String hash = inspected.hash();
        SourceMetadata afterHash = SourceMetadata.of(relPath, Files.readAttributes(file, BasicFileAttributes.class));
        long size = inspected.length();
        boolean changedDuringRead = !before.equals(afterHash) || size != afterHash.files().get(0).size();

        if (blobStore.exists(hash, "zst")) {
            long compressedSize = blobStore.size(blobStore.getRelativePath(hash, "zst"));
            result.addFile(new FileEntry(
                    relPath, hash, blobStore.getRelativePath(hash, "zst"),
                    compressedSize, size, changedDuringRead ? SourceMetadata.empty() : afterHash));
            if (changedDuringRead)
                LOG.warn("backup_source_changed path={} kind=file action=record_content", quote(relPath));
            result.changedFiles++;
            return;
        }

        try (var session = blobStore.newWriteSession(); var input = Files.newInputStream(file)) {
            var actual = ZstdStream.write(input, session.output(), compressionLevel);
            SourceMetadata afterWrite = SourceMetadata.of(relPath, Files.readAttributes(file, BasicFileAttributes.class));
            boolean changed = changedDuringRead || !hash.equals(actual.hash()) || size != actual.length()
                    || !afterHash.equals(afterWrite);
            if (changed)
                LOG.warn("backup_source_changed path={} kind=file action=commit_actual", quote(relPath));
            var commit = session.commit(actual.hash(), "zst");
            result.addFile(new FileEntry(relPath, actual.hash(), commit.path(), commit.size(), actual.length(),
                    changed ? SourceMetadata.empty() : afterWrite));
            result.changedFiles++;
            if (commit.created())
                result.newBytes += commit.size();
        }
    }

    // ──────────────────────────────────────────────
    // Content-hash dedup (in-memory, no .lrh read)
    // ──────────────────────────────────────────────

    McaHasher.FileHash inspectExtraFile(Path file) throws IOException {
        return McaHasher.inspectFile(file);
    }

    /**
     * Look up a stored blob path by entry type + content identity.
     * <p>
     * Uses the in-memory {@link MetadataIndex} — no disk I/O.
     * Returns {@code null} if the index is unavailable or no match is found.
     */
    private String findBlobByContentHash(EntryMetadata.Type type, String contentHashHex) {
        MetadataIndex metadataIndex = indexSupplier.get();
        if (metadataIndex == null)
            return null;
        String path = metadataIndex.findBlobByContentHash(type, contentHashHex);
        return path != null && blobStore.existsPath(path) ? path
                : blobStore.exists(contentHashHex, type == EntryMetadata.Type.REGION ? "lrh" : "zst")
                        ? blobStore.getRelativePath(contentHashHex, type == EntryMetadata.Type.REGION ? "lrh" : "zst")
                        : null;
    }

    // ──────────────────────────────────────────────
    // Region outcome and dedup logging
    // ──────────────────────────────────────────────

    private record RegionOutcome(RegionEntry entry, boolean changed, long newBytes) {
        static RegionOutcome success(RegionEntry entry, boolean changed, long newBytes) {
            return new RegionOutcome(entry, changed, newBytes);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static final class DedupStats {
        final String snapshot;
        final long start = System.nanoTime();
        final java.util.concurrent.atomic.LongAdder metadataHits = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder checks = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder misses = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder errors = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder hashMs = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder writeMs = new java.util.concurrent.atomic.LongAdder();

        DedupStats(String snapshot) {
            this.snapshot = snapshot;
        }

        void log() {
            if (LOG.isDebugEnabled())
                LOG.debug(
                        "backup_dedup_summary snapshot={} metadata_hits={} content_checks={} content_hits={} content_misses={} errors={} content_hash_ms_total={} write_ms_total={} elapsed_ms={} content_hit_rate={}",
                        snapshot, metadataHits.sum(), checks.sum(), hits.sum(), misses.sum(), errors.sum(), hashMs.sum(),
                        writeMs.sum(),
                        (System.nanoTime() - start) / 1_000_000,
                        checks.sum() == 0 ? "n/a" : Double.toString((double) hits.sum() / checks.sum()));
        }
    }
    // ──────────────────────────────────────────────
    // Latest-snapshot map builders
    // ──────────────────────────────────────────────

    private static Map<String, RegionEntry> buildRegionMap(SnapshotManifest snapshot) {
        if (snapshot == null)
            return Collections.emptyMap();
        Map<String, RegionEntry> map = new HashMap<>();
        if (snapshot.getRegions() != null) {
            for (RegionEntry r : snapshot.getRegions()) {
                map.put(r.getFilename(), r);
            }
        }
        return map;
    }

    private static Map<String, FileEntry> buildFileMap(SnapshotManifest snapshot) {
        if (snapshot == null)
            return Collections.emptyMap();
        Map<String, FileEntry> map = new HashMap<>();
        if (snapshot.getExtraFiles() != null) {
            for (FileEntry f : snapshot.getExtraFiles()) {
                map.put(f.getFilename(), f);
            }
        }
        return map;
    }
}
