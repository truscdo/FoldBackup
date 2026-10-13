package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.backup.BackupResourceBudget;
import io.github.truscdo.foldbackup.core.format.LrhFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Archive creator: online snapshot → self-contained tar archive.
 * <p>
 * Full archive ({@link #createFull}): writes one object per distinct Region or
 * file content identity and stores the complete path manifest in {@code snapshot.bin}.
 * <p>
 * Differential archive ({@link #createDiff}): chunk-level {@code .lrhd}
 * files for changed Regions, changed file objects, and a complete target manifest.
 * <p>
 * Every entry is streamed into the tar: region blobs are recompressed straight
 * from the BlobStore channel through a bounded worker window, compressed file
 * objects are copied from the BlobStore, and each {@code .lrhd} is written
 * directly into the archive. No complete region, payload or archive is held in
 * memory.
 * <p>
 * The archive directory is fully external — the online system (index.db /
 * BlobStore) is only read here, never written.
 */
public class ArchiveCreator {

    /** 共享块池与 TAR 输出的块粒度。 */
    private static final int REGION_BLOCK_BYTES = 64 * 1024;

    private final SnapshotStore snapshotManager;
    private final BlobStore blobStore;
    private final int compressionLevel;

    public ArchiveCreator(SnapshotStore snapshotManager, BlobStore blobStore, int compressionLevel) {
        this.snapshotManager = snapshotManager;
        this.blobStore = blobStore;
        this.compressionLevel = compressionLevel;
    }

    // ── Full archive creation ──

    /**
     * Create a full archive tar: {@code S-yyyyMMdd_HHmmss[full].tar}.
     * <p>
     * Defaults: recompress regions at {@link ArchiveDefaults#RECOMPRESS_LEVEL}
     * with auto-computed parallelism for the standalone CLI/TUI process.
     *
     * @param epochSeconds online snapshot identity to export
     * @param outputDir  archive directory (created if absent)
     * @return path to the written tar file
     */
    public Path createFull(long epochSeconds, Path outputDir) throws IOException {
        return createFull(epochSeconds, outputDir, true, 0);
    }

    /**
     * Create a full archive tar with explicit recompress / parallelism control.
     * <p>
     * When {@code recompress} is true, each distinct {@code .lrh} object is parsed and
     * re-written at this creator's configured compression level (contentHash is
     * unchanged — it covers only the size-only header table + chunk NBT, not
     * the compressed bytes). When false, blobs are copied verbatim.
     * <p>
     * Recompression runs on a bounded producer-consumer pipeline: worker
     * threads read → decompress → recompress, the single writer thread
     * serializes into the tar (TarWriter is not thread-safe). Results are
     * selected by readiness independently of manifest order. A shared bounded
     * block pool reserves capacity for the current entry; at most twice the
     * worker count may be in flight, including completed but unwritten regions.
     *
     * @param epochSeconds online snapshot identity to export
     * @param outputDir  archive directory (created if absent)
     * @param recompress true = recompress at the configured level (default 19); false = verbatim copy
     * @param threads    worker count; &lt;= 0 = auto (all visible processors,
     *                   bounded by the memory budget and by the work items)
     * @return path to the written tar file
     */
    public Path createFull(long epochSeconds, Path outputDir, boolean recompress, int threads) throws IOException {
        return createFullWithProgress(epochSeconds, outputDir, recompress, threads, null);
    }

    /** Create a full archive using the common structured progress protocol. */
    public Path createFullWithProgress(long epochSeconds, Path outputDir, boolean recompress, int threads,
            ProgressListener listener) throws IOException {
        var progress = new ProgressTracker(listener);
        progress.stage("Loading snapshot", 1);
        SnapshotManifest sn = snapshotManager.loadSnapshot(epochSeconds);
        if (sn == null) {
            throw new IOException("Snapshot not found at epoch: " + epochSeconds);
        }
        Files.createDirectories(outputDir);
        progress.done(sn.getSnapshotId());

        // Archive snapshot metadata uses the same Unix epoch seconds as the
        // online snapshot manifest; do not divide it a second time.
        epochSeconds = sn.getEpochSeconds();
        String tarName = fullTarName(sn.getSnapshotId());
        Path tarPath = outputDir.resolve(tarName);

        // Build snapshot.bin metadata
        ArchiveSnapshot meta = new ArchiveSnapshot();
        meta.type = "full";
        meta.sourceSnapshotId = sn.getSnapshotId();
        meta.epoch = epochSeconds;
        meta.world = sn.getWorldName();
        meta.minecraftVersion = sn.getMinecraftVersion();
        meta.worldVersion = sn.getWorldVersion();
        if (sn.getTags() != null) {
            meta.tags = sn.getTags();
        }
        meta.regionCount = sn.getRegions() != null ? sn.getRegions().size() : 0;
        meta.chunkCount = 0;
        if (sn.getExtraFiles() != null) for (FileEntry file : sn.getExtraFiles())
            meta.files.add(new ArchiveSnapshot.FileInfo(file.getFilename(), file.getSizeUncompressed(),
                    file.getSourceHash(), file.getSourceMetadata()));
        progress.stage("Reading metadata", meta.regionCount);
        if (sn.getRegions() != null) {
            for (RegionEntry re : sn.getRegions()) {
                progress.current(re.getFilename());
                meta.regions.add(new ArchiveSnapshot.RegionInfo(re.getFilename(), re.getSizeUncompressed(),
                        re.getContentHash(), re.getSourceMetadata()));
                // Lightweight preheader read — chunk_count (i32 BE at offset 19)
                byte[] prefix = blobStore.readPrefix(re.getBlobPath(), 23);
                meta.chunkCount += readChunkCountFromLrh(prefix);
                progress.done(re.getFilename());
            }
        }

        // One LRH object per distinct content identity; snapshot.bin maps every path to it.
        // The parallel work unit is a unique object, so the worker clamp must use this count
        // rather than the (larger) number of manifest region paths.
        List<RegionEntry> uniqueRegions = uniqueRegions(sn.getRegions());

        // 配额只算一次：预算、预留、每 worker 内存都交给 BackupResourceBudget.regionQuota。
        var plan = planRegions(uniqueRegions, ArchiveDefaults.exportMemoryBudget(), compressionLevel, threads);

        // 原子提交：写 .tmp → 完成后 rename；失败删除 .tmp，不留半成品
        Path tmpPath = tarPath.resolveSibling(tarPath.getFileName() + ".tmp");
        refuseExistingArchive(tarPath, tmpPath);
        try {
            try (var channel = java.nio.channels.FileChannel.open(tmpPath, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
                    TarWriter tar = new TarWriter(channel)) {
                // snapshot.bin first
                tar.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(meta));

                progress.stage(recompress ? "Recompressing regions" : "Copying regions", uniqueRegions.size());
                if (!uniqueRegions.isEmpty()) {
                    if (recompress && plan.quota().workers() > 1) {
                        exportRegionsParallel(tar, uniqueRegions, plan.quota(), plan.bufferBytes(),
                                compressionLevel, progress);
                    } else if (recompress) {
                        for (RegionEntry re : uniqueRegions) {
                            String object = regionObjectPath(re.getContentHash());
                            progress.current(object);
                            tar.putEntry(object, out -> LrhFormat.recompress(
                                    blobStore.getRootDir().resolve(re.getBlobPath()), out,
                                    compressionLevel));
                            progress.done(object);
                        }
                    } else {
                        for (RegionEntry re : uniqueRegions) {
                            String object = regionObjectPath(re.getContentHash());
                            progress.current(object);
                            try (var input = blobStore.open(re.getBlobPath())) {
                                tar.putEntry(object, input, blobStore.size(re.getBlobPath()));
                            }
                            progress.done(object);
                        }
                    }
                }

                // Store each distinct compressed file payload once.
                progress.stage("Copying files", sn.getExtraFiles() == null ? 0 : sn.getExtraFiles().size());
                if (sn.getExtraFiles() != null) {
                    Set<String> written = new HashSet<>();
                    for (FileEntry fe : sn.getExtraFiles()) {
                        String object = fileObjectPath(fe.getSourceHash());
                        progress.current(object);
                        if (written.add(fe.getSourceHash())) try (var input = blobStore.open(fe.getBlobPath())) {
                            tar.putEntry(object, input, blobStore.size(fe.getBlobPath()));
                        }
                        progress.done(object);
                    }
                }
                progress.stage("Finalizing archive", 1);
                progress.current(tarName);
            }
            Files.move(tmpPath, tarPath);
            progress.done(tarName);
        } catch (IOException | RuntimeException | Error e) {
            try { Files.deleteIfExists(tmpPath); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return tarPath;
    }

    // ── Differential archive creation ──

    /**
     * Create a differential archive tar:
     * {@code S-base[S-target].tar}.
     * <p>
     * Baseline = the online full snapshot {@code baseSnapshotId}; changed
     * chunks are written as {@code .lrhd}, and file changes are represented by
     * {@code files.bin} plus content-addressed file objects.
     *
     * @param baseSnapshotId online full snapshot (baseline)
     * @param diffSnapshotId online snapshot at the diff time point
     * @param outputDir      archive directory (created if absent)
     * @return path to the written diff tar file
     */
    public Path createDiff(long baseEpochSeconds, long diffEpochSeconds, Path outputDir) throws IOException {
        return createDiffWithProgress(baseEpochSeconds, diffEpochSeconds, outputDir, null);
    }

    public Path createDiff(long baseEpochSeconds, long diffEpochSeconds, Path outputDir, int threads) throws IOException {
        return createDiffWithProgress(baseEpochSeconds, diffEpochSeconds, outputDir, threads, null);
    }

    /** Create a differential archive using the common structured progress protocol. */
    public Path createDiffWithProgress(long baseEpochSeconds, long diffEpochSeconds, Path outputDir,
            ProgressListener listener) throws IOException {
        return createDiffWithProgress(baseEpochSeconds, diffEpochSeconds, outputDir, 0, listener);
    }

    /** Bounded Region comparison followed by per-directory frame production; <= 0 threads means auto. */
    public Path createDiffWithProgress(long baseEpochSeconds, long diffEpochSeconds, Path outputDir,
            int threads, ProgressListener listener) throws IOException {
        var progress = new ProgressTracker(listener);
        progress.stage("Loading snapshots", 1);
        SnapshotManifest base = snapshotManager.loadSnapshot(baseEpochSeconds);
        SnapshotManifest diff = snapshotManager.loadSnapshot(diffEpochSeconds);
        if (base == null) {
            throw new IOException("Base snapshot not found at epoch: " + baseEpochSeconds);
        }
        if (diff == null) {
            throw new IOException("Diff snapshot not found at epoch: " + diffEpochSeconds);
        }
        Files.createDirectories(outputDir);
        progress.done(diff.getSnapshotId());

        baseEpochSeconds = base.getEpochSeconds();
        diffEpochSeconds = diff.getEpochSeconds();
        String tarName = diffTarName(base.getSnapshotId(), diff.getSnapshotId());
        Path tarPath = outputDir.resolve(tarName);

        // Build snapshot.bin metadata
        ArchiveSnapshot meta = new ArchiveSnapshot();
        meta.type = "diff";
        meta.sourceSnapshotId = diff.getSnapshotId();
        meta.epoch = diffEpochSeconds;
        meta.baseEpoch = baseEpochSeconds;
        meta.world = diff.getWorldName();
        meta.minecraftVersion = diff.getMinecraftVersion();
        meta.worldVersion = diff.getWorldVersion();
        if (diff.getTags() != null) {
            meta.tags = diff.getTags();
        }
        meta.regionCount = diff.getRegions() != null ? diff.getRegions().size() : 0;
        meta.chunkCount = 0;
        if (diff.getExtraFiles() != null) for (FileEntry file : diff.getExtraFiles())
            meta.files.add(new ArchiveSnapshot.FileInfo(file.getFilename(), file.getSizeUncompressed(),
                    file.getSourceHash(), file.getSourceMetadata()));
        progress.stage("Reading metadata", meta.regionCount);
        if (diff.getRegions() != null) {
            for (RegionEntry re : diff.getRegions()) {
                progress.current(re.getFilename());
                meta.regions.add(new ArchiveSnapshot.RegionInfo(re.getFilename(), re.getSizeUncompressed(),
                        re.getContentHash(), re.getSourceMetadata()));
                byte[] prefix = blobStore.readPrefix(re.getBlobPath(), 23);
                meta.chunkCount += readChunkCountFromLrh(prefix);
                progress.done(re.getFilename());
            }
        }

        // 原子提交：写 .tmp → 完成后 rename；失败删除 .tmp
        Path tmpPath = tarPath.resolveSibling(tarPath.getFileName() + ".tmp");
        refuseExistingArchive(tarPath, tmpPath);
        try {
            try (var scratch = new ArchiveTemporaryDirectory();
                    var result = new StreamingDiff(blobStore, scratch.path)) {
                result.compare(base, diff, progress);
                progress.stage("Writing archive", 1 + result.directories.size()
                        + (result.filesBin == null ? 0 : 1) + result.fileObjects.size());
                try (var channel = java.nio.channels.FileChannel.open(tmpPath, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                        TarWriter tar = new TarWriter(channel)) {
                    tar.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
                    progress.done("snapshot.bin");

                    // .lrhd per changed directory
                    result.writeDirectories(tar, compressionLevel, threads, progress);

                    // files.bin (non-.mca changes and whole-region deletions)
                    if (result.filesBin != null) {
                        tar.putEntry("files.bin", result.filesBin);
                        progress.done("files.bin");
                    }

                    // Changed file payloads are stored once by content identity.
                    for (FileEntry fe : result.fileObjects) {
                        String object = fileObjectPath(fe.getSourceHash());
                        progress.current(object);
                        try (var input = blobStore.open(fe.getBlobPath())) {
                            tar.putEntry(object, input, blobStore.size(fe.getBlobPath()));
                        }
                        progress.done(object);
                    }
                    progress.stage("Finalizing archive", 1);
                    progress.current(tarName);
                }
            }
            Files.move(tmpPath, tarPath);
            progress.done(tarName);
        } catch (IOException | RuntimeException | Error e) {
            try { Files.deleteIfExists(tmpPath); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return tarPath;
    }

    // ── helpers ──

    private static void refuseExistingArchive(Path tarPath, Path tmpPath) throws IOException {
        if (Files.exists(tarPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Archive already exists: " + tarPath);
        if (Files.exists(tmpPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Archive temporary file already exists: " + tmpPath);
    }

    public static String regionObjectPath(String contentHash) {
        return "objects/regions/" + contentHash + ".lrh";
    }

    public static String fileObjectPath(String sourceHash) {
        return "objects/files/" + sourceHash + ".zst";
    }

    private static List<RegionEntry> uniqueRegions(List<RegionEntry> regions) {
        if (regions == null || regions.isEmpty()) return List.of();
        Map<String, RegionEntry> unique = new LinkedHashMap<>();
        for (RegionEntry region : regions) unique.putIfAbsent(region.getContentHash(), region);
        return List.copyOf(unique.values());
    }

    /** Read chunk_count (i32 BE at offset 19) from a .lrh preheader. */
    static int readChunkCountFromLrh(byte[] lrh) {
        if (lrh.length < 23) {
            return 0;
        }
        return ByteBuffer.wrap(lrh, 19, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static String fullTarName(String snapshotId) {
        return snapshotId + "[full].tar";
    }

    public static String diffTarName(String baseId, String targetId) {
        return baseId + "[" + targetId + "].tar";
    }

    /** 一次导出的配额；{@code quota} 与块池容量同源，避免两处公式漂移。 */
    record RegionPlan(BackupResourceBudget.RegionQuota quota, long bufferBytes) {}

    /**
     * 全量归档唯一的一处配额来源：算出配额与块池容量，之后不再有任何收窄。
     * <p>
     * 每个 worker 同时在飞两份上下文——解码源 blob 的 LRH 帧、以及以 {@code level} 重压——故每
     * worker 内存取两者之和。源帧级别按本批取上界，只读每份 blob 的头前缀。
     */
    RegionPlan planRegions(List<RegionEntry> regions, long budgetBytes, int level, int threads) {
        long bufferBytes = BackupResourceBudget.regionBufferBytes(budgetBytes, REGION_BLOCK_BYTES);
        int sourceFrameLevel = ArchiveTasks.maxSourceFrameLevel(
                regions.stream().map(re -> blobStore.getRootDir().resolve(re.getBlobPath())).toList());
        var quota = BackupResourceBudget.regionQuota(regions.size(), budgetBytes, bufferBytes,
                BackupResourceBudget.compressAndDecodeTaskBytes(level, sourceFrameLevel),
                Runtime.getRuntime().availableProcessors(), threads);
        return new RegionPlan(quota, bufferBytes);
    }

    OrderedCompressionPipeline.Metrics exportRegionsParallel(TarWriter tar, List<RegionEntry> regions,
            BackupResourceBudget.RegionQuota quota, long bufferBytes, int level, ProgressTracker progress)
            throws IOException {
        return OrderedCompressionPipeline.<io.github.truscdo.foldbackup.core.format.RegionStream.Summary>run(
                regions.size(), quota.workers(), quota.inFlightWindow(), REGION_BLOCK_BYTES, bufferBytes,
                (index, output) -> LrhFormat.compress(output,
                        consumer -> LrhFormat.read(blobStore.getRootDir().resolve(
                                regions.get(index).getBlobPath()), consumer), level),
                (index, result) -> {
                    RegionEntry entry = regions.get(index);
                    String object = regionObjectPath(entry.getContentHash());
                    progress.current(object);
                    tar.putEntry(object, output -> {
                        long start = output.position();
                        io.github.truscdo.foldbackup.core.format.StreamIo.write(output, new byte[LrhFormat.COMPRESSED_DATA_OFFSET]);
                        var summary = result.drainTo(io.github.truscdo.foldbackup.core.format.StreamIo.output(output));
                        LrhFormat.finish(output, start, summary, level);
                    });
                    progress.done(object);
                });
    }
}
