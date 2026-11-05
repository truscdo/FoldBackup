package io.github.truscdo.foldbackup.core.export;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.restore.RestoreOperation;
import io.github.truscdo.foldbackup.core.restore.SnapshotRestoreSource;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把在线快照导出为单个存档 ZIP，CLI 与 TUI 共用同一套实现。
 * <p>
 * 世界先重建在目标 ZIP 同目录的暂存目录里，复用 {@link RestoreOperation} 的写入骨架，
 * 然后整棵目录打包并原子替换目标文件。暂存目录每次都是新建的，因此没有需要检查、
 * 跳过或删除的目标内容；也不取得世界锁。
 */
public final class SnapshotZipExport {
    private static final String ZIP_SUFFIX = ".zip";

    private SnapshotZipExport() {
    }

    /**
     * 规范化输出路径：缺少 {@code .zip} 时自动补全（{@code foo.tar} 变成 {@code foo.tar.zip}）。
     *
     * @throws IOException 路径为空白、没有文件名，或补全后指向已存在的目录
     */
    public static Path resolveTarget(Path requested) throws IOException {
        if (requested == null || requested.toString().isBlank())
            throw new IOException("Output path is required");
        Path target = requested.toAbsolutePath().normalize();
        if (target.getFileName() == null)
            throw new IOException("Output path must name a file: " + requested);
        String name = target.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(ZIP_SUFFIX))
            target = target.resolveSibling(name + ZIP_SUFFIX);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("ZIP output path is a directory: " + target);
        return target;
    }

    /**
     * 导出指定快照为 ZIP。
     *
     * @param snapshots    在线快照库
     * @param blobs        内容 blob 库
     * @param snapshotId   快照 ID
     * @param requestedOutput 目标 ZIP 路径，缺少 {@code .zip} 时自动补全
     * @param threads      区域写入线程数，0 表示按内存预算自动选择
     * @param listener     进度监听，可为 {@code null}
     * @return 实际写出的 ZIP 路径
     */
    public static Path export(SnapshotStore snapshots, BlobStore blobs, String snapshotId,
            Path requestedOutput, int threads, ProgressListener listener) throws IOException {
        Path target = resolveTarget(requestedOutput);
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Output path has no parent: " + target);
        Files.createDirectories(parent);

        Path stage = Files.createTempDirectory(parent, "." + target.getFileName() + ".foldbackup-");
        Path stagedZip = stage.resolveSibling(stage.getFileName() + ZIP_SUFFIX);
        try {
            long epoch = snapshots.resolveEpoch(snapshotId);
            SnapshotManifest manifest = snapshots.loadSnapshot(epoch);
            if (manifest == null) throw new IOException("Snapshot not found: " + snapshotId);

            // 暂存目录为空，覆盖流程的检查阶段不读取任何 blob，删除阶段不执行；也不取得世界锁。
            try (var source = new SnapshotRestoreSource(manifest, blobs)) {
                RestoreOperation.run(source, stage,
                        new RestoreOperation.Options(false, false, threads, ArchiveDefaults.EXPORT_LEVEL),
                        listener);
            }
            pack(stage, stagedZip, listener);
            moveReplacing(stagedZip, target);
            return target;
        } finally {
            deleteQuietly(stagedZip);
            deleteTreeQuietly(stage);
        }
    }

    /** 打包暂存目录，条目名使用 {@code /} 分隔，并沿用文件已恢复的修改时间。 */
    private static void pack(Path root, Path zipPath, ProgressListener listener) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        var progress = new ProgressTracker(listener);
        progress.stage("Packaging", files.size());
        try (OutputStream out = Files.newOutputStream(zipPath);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path file : files) {
                String name = root.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
                progress.done(name);
            }
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 残留的暂存文件不影响已完成的导出结果。
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            return;
        }
        for (Path path : paths) deleteQuietly(path);
    }
}
