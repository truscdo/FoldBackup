package io.github.truscdo.foldbackup.core;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;
import io.github.truscdo.foldbackup.core.format.AnvilFormat;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.archive.SnapshotWorldRestorer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将快照从数据块存储导出到可读的世界目录。
 * <p>
 * 支持两种输出格式：
 * <ul>
 * <li>{@code mca} — Anvil (.mca) 区域文件（默认，用于直接游玩）</li>
 * <li>{@code linear} — 线性 (.lrh) 区域文件（用于格式检查）</li>
 * </ul>
 * 输出可选打包为单个 zip 归档。
 */
public class BackupExporter {

    public enum Format {
        MCA, LINEAR
    }

    private final SnapshotStore snapshotManager;
    private final BlobStore blobStore;
    private final int compressionLevel;

    public BackupExporter(SnapshotStore snapshotManager, BlobStore blobStore, int compressionLevel) {
        this.snapshotManager = snapshotManager;
        this.blobStore = blobStore;
        this.compressionLevel = compressionLevel;
    }

    // --- 列表 ---

    /**
     * 在 list 命令中显示的单个快照摘要信息。
     */
    public static class SnapshotInfo {
        public final long epochSeconds;
        public final String id;
        public final String date;
        public final int regions;
        public final int files;
        public final long compressedBytes;
        public final long uncompressedBytes;
        /** Cumulative deduplicated blob space through this snapshot in the deletion chain. */
        public final long cumulativeSpaceBytes;

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, 0);
        }

        public SnapshotInfo(String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this(0, id, date, regions, files, compressedBytes, uncompressedBytes, cumulativeSpaceBytes);
        }

        public SnapshotInfo(long epochSeconds, String id, String date, int regions, int files,
                long compressedBytes, long uncompressedBytes, long cumulativeSpaceBytes) {
            this.epochSeconds = epochSeconds;
            this.id = id;
            this.date = date;
            this.regions = regions;
            this.files = files;
            this.compressedBytes = compressedBytes;
            this.uncompressedBytes = uncompressedBytes;
            this.cumulativeSpaceBytes = cumulativeSpaceBytes;
        }
    }

    /**
     * 列出所有可用快照及其摘要信息。
     */
    public List<SnapshotInfo> listSnapshots() {
        List<SnapshotManifest> all = snapshotManager.loadAllSnapshots();
        return toSnapshotInfo(all, Map.of());
    }

    /**
     * 列出快照摘要，并计算去重后的累计链式空间。
     * <p>
     * 链式估算只执行一次全量扫描，结果按 Epoch 秒回填；不会为每个快照重复计算。
     */
    public List<SnapshotInfo> listSnapshotsWithCumulativeSpace() {
        List<SnapshotManifest> all = snapshotManager.loadAllSnapshots();
        Map<Long, Long> cumulativeSpaceByEpoch = new HashMap<>();
        for (SnapshotReclamationEstimator.DeletionEstimate estimate
                : SnapshotReclamationEstimator.evaluateDeletionChain(all)) {
            cumulativeSpaceByEpoch.put(estimate.epochSeconds(), estimate.cumulativeReclaimedBytes());
        }
        return toSnapshotInfo(all, cumulativeSpaceByEpoch);
    }

    private List<SnapshotInfo> toSnapshotInfo(List<SnapshotManifest> snapshots,
                                               Map<Long, Long> cumulativeSpaceByEpoch) {
        List<SnapshotInfo> result = new ArrayList<>(snapshots.size());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (SnapshotManifest sn : snapshots) {
            String date = sdf.format(Date.from(Instant.ofEpochSecond(sn.getEpochSeconds())));
            SnapshotManifest.Summary s = sn.getSummary();
            int regions = (s != null) ? s.getRegionsTotal() : 0;
            int files = (s != null) ? s.getExtraFilesTotal() : 0;
            long compressed = (s != null) ? s.getTotalCompressedBytes() : 0;
            long uncompressed = (s != null) ? s.getTotalUncompressedBytes() : 0;
            result.add(new SnapshotInfo(sn.getEpochSeconds(), sn.getSnapshotId(), date, regions, files,
                    compressed, uncompressed, cumulativeSpaceByEpoch.getOrDefault(sn.getEpochSeconds(), 0L)));
        }
        return result;
    }

    /**
     * 返回 Epoch 严格早于目标快照的候选（归档创建 diff 的合法基础候选），
     * 按 {@link #listSnapshots()} 顺序。
     */
    public List<SnapshotInfo> diffBaseCandidates(long targetEpochSeconds) {
        List<SnapshotInfo> candidates = new ArrayList<>();
        for (SnapshotInfo sn : listSnapshots()) {
            if (sn.epochSeconds < targetEpochSeconds) {
                candidates.add(sn);
            }
        }
        return candidates;
    }

    // --- 导出 ---

    /**
     * Export a snapshot to the given output directory (no progress callback).
     *
     * @param epochSeconds internal snapshot identity
     * @param outputPath target directory (created if absent)
     * @param format     region output format ({@code MCA} or {@code LINEAR})
     * @param zip        if true, package the output as a zip and delete the
     *                   directory
     * @return the path to the exported output (directory or zip file)
     * @throws IOException if the snapshot is not found or I/O fails
     */
    public Path export(long epochSeconds, Path outputPath, Format format, boolean zip) throws IOException {
        return exportWithProgress(epochSeconds, outputPath, format, zip, null);
    }

    /**
     * Export a snapshot to the given output directory with progress callback.
     * <p>
     * Region entries are re-encoded by streaming: {@code LINEAR} recompresses the
     * stored LRH frame into a temporary file, {@code MCA} expands it into a fresh
     * Anvil file, and a whole region is never buffered.
     * <p>
     * @param epochSeconds     internal snapshot identity
     * @param outputPath       target directory (created if absent)
     * @param format           region output format ({@code MCA} or {@code LINEAR})
     * @param zip              if true, package the output as a zip and delete the
     *                         directory
     * @param progressListener progress listener, may be {@code null}
     * @return the path to the exported output (directory or zip file)
     * @throws IOException if the snapshot is not found or I/O fails
     */
    public Path exportWithProgress(long epochSeconds, Path outputPath, Format format, boolean zip,
            ProgressListener progressListener) throws IOException {
        return exportWithProgress(epochSeconds, outputPath, format, zip, 0, progressListener);
    }

    /** Export with an explicit Region worker cap; zero selects the memory-bounded default. */
    public Path exportWithProgress(long epochSeconds, Path outputPath, Format format, boolean zip,
            int threads, ProgressListener progressListener) throws IOException {
        SnapshotManifest snapshot = snapshotManager.loadSnapshot(epochSeconds);
        if (snapshot == null) {
            throw new IOException("Snapshot not found at epoch: " + epochSeconds);
        }

        var progress = new ProgressTracker(progressListener);
        if (format == Format.MCA) {
            new SnapshotWorldRestorer(blobStore, compressionLevel, threads)
                    .restore(snapshot, outputPath, progressListener);
        } else {
            Files.createDirectories(outputPath);

            // 预计算总项目数以显示进度
            int regionCount = snapshot.getRegions() != null ? snapshot.getRegions().size() : 0;
            int fileCount = snapshot.getExtraFiles() != null ? snapshot.getExtraFiles().size() : 0;
            int totalItems = regionCount + fileCount;
            progress.stage("Exporting", totalItems);

            // 导出区域
            if (snapshot.getRegions() != null) {
                for (RegionEntry entry : snapshot.getRegions()) {
                    progress.current(entry.getFilename());

                    int[] coords = parseRegionCoords(entry.getFilename());

                    String outName = "r." + coords[0] + "." + coords[1] + ".lrh";
                    Path relative = Path.of(entry.getFilename());
                    Path parent = relative.getParent();
                    Path outFile = outputPath.resolve(parent == null ? Path.of(outName) : parent.resolve(outName))
                            .normalize();
                    if (relative.isAbsolute()
                            || !outFile.toAbsolutePath().startsWith(outputPath.toAbsolutePath().normalize())) {
                        throw new IOException("Invalid region export path: " + entry.getFilename());
                    }

                    Files.createDirectories(outFile.getParent());
                    Path temp = Files.createTempFile(outFile.getParent(), outFile.getFileName() + ".", ".wip");
                    try {
                        try (var channel = java.nio.channels.FileChannel.open(temp,
                                java.nio.file.StandardOpenOption.WRITE)) {
                            LrhFormat.recompress(blobStore.getRootDir().resolve(entry.getBlobPath()), channel,
                                    compressionLevel);
                            channel.force(true);
                        }
                        Files.move(temp, outFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                    progress.done(entry.getFilename());
                }
            }

            // 导出额外文件
            if (snapshot.getExtraFiles() != null) {
                for (FileEntry entry : snapshot.getExtraFiles()) {
                    progress.current(entry.getFilename());

                    Path outFile = outputPath.resolve(entry.getFilename());
                    Files.createDirectories(outFile.getParent());
                    try (var input = blobStore.openFile(entry.getBlobPath()); var output = Files.newOutputStream(outFile)) {
                        input.transferTo(output);
                    }
                    progress.done(entry.getFilename());
                }
            }
        }

        // 可选打包 zip
        if (zip) {
            progress.stage("Packaging", 1);
            progress.current("packaging...");

            Path zipPath = outputPath.resolveSibling(outputPath.getFileName() + ".zip");
            try (OutputStream fos = Files.newOutputStream(zipPath);
                    ZipOutputStream zos = new ZipOutputStream(fos)) {
                Path root = outputPath;
                Files.walk(root).filter(p -> !Files.isDirectory(p)).forEach(p -> {
                    String entry = root.relativize(p).toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to zip " + entry, e);
                    }
                });
            }
            // 删除临时目录
            try (var stream = Files.walk(outputPath)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }

            progress.done("done");
            return zipPath;
        }

        return outputPath;
    }

    /** 从文件名（如 "region/r.0.0.mca"）解析区域 X/Z 坐标。 */
    private static int[] parseRegionCoords(String filename) {
        // 文件名可能是 "region/r.0.0.mca"——去掉目录前缀
        String name = filename.contains("/") ? filename.substring(filename.lastIndexOf('/') + 1) : filename;
        return AnvilFormat.parseRegionCoords(name);
    }
}
