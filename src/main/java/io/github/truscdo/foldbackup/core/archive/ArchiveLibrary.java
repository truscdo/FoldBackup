package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次扫描得到的归档库视图：每个 tar 及其 {@code snapshot.bin}，按 Epoch 升序，full 已按 Epoch 去重。
 * <p>
 * 扫描是整次导入唯一的目录遍历与元数据读取；导入不再按 diff 反复扫描。
 * <p>
 * 同 Epoch 多份 full 归档：`snapshot.bin` 字节一致时按文件名排序保留第一份并给出警告，不一致时
 * 记为错误（该 Epoch 不导入）。导入顺序依赖 Epoch 升序，因此 full 必先于其 diff 处理。
 */
final class ArchiveLibrary {

    record Entry(Path tar, ArchiveSnapshot meta) {
        String fileName() {
            return tar.getFileName().toString();
        }
    }

    private final List<Entry> entries;
    private final List<String> warnings;
    private final List<String> errors;

    private ArchiveLibrary(List<Entry> entries, List<String> warnings, List<String> errors) {
        this.entries = entries;
        this.warnings = warnings;
        this.errors = errors;
    }

    static ArchiveLibrary scan(Path root) throws IOException {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Path> tars = tarFiles(root);

        Map<Long, Entry> fulls = new HashMap<>();
        Map<Long, byte[]> fullMetadata = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Path tar : tars) {
            String fileName = tar.getFileName().toString();
            Scanned scanned = readArchive(tar, fileName, errors);
            if (scanned == null) continue;
            ArchiveSnapshot meta = scanned.meta();
            Entry entry = new Entry(tar, meta);
            if (!meta.isFull()) {
                entries.add(entry);
                continue;
            }
            Entry kept = fulls.get(meta.epoch);
            if (kept == null) {
                fulls.put(meta.epoch, entry);
                fullMetadata.put(meta.epoch, scanned.bytes());
            } else if (Arrays.equals(fullMetadata.get(meta.epoch), scanned.bytes())) {
                warnings.add("duplicate full archive for epoch " + meta.epoch + ": keeping "
                        + kept.fileName() + ", ignoring " + fileName);
            } else {
                error(errors, fileName, "duplicate full archive for epoch " + meta.epoch
                        + " differs from " + kept.fileName());
            }
        }
        entries.addAll(fulls.values());
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.meta().epoch)
                .thenComparing(Entry::fileName));
        return new ArchiveLibrary(entries, warnings, errors);
    }

    /** 目录内全部 {@code .tar}，按文件名排序：同 Epoch 去重时“保留第一份”才是确定的。 */
    private static List<Path> tarFiles(Path root) throws IOException {
        List<Path> tars = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (path.getFileName().toString().endsWith(".tar")) tars.add(path);
            }
        }
        tars.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return tars;
    }

    /**
     * 读取并解析一个归档的 {@code snapshot.bin}；不可导入时把原因记入 {@code errors} 并返回
     * {@code null}，扫描继续处理下一个归档。
     */
    private static Scanned readArchive(Path tar, String fileName, List<String> errors) throws IOException {
        byte[] bytes;
        try {
            bytes = readSnapshotBytes(tar);
        } catch (IOException | RuntimeException e) {
            error(errors, fileName, e.getMessage());
            return null;
        }
        if (bytes == null) {
            error(errors, fileName, "snapshot.bin is missing");
            return null;
        }
        ArchiveSnapshot meta;
        try {
            meta = ArchiveSnapshotCodec.decode(bytes);
        } catch (RuntimeException e) {
            error(errors, fileName, e.getMessage());
            return null;
        }
        if (meta == null) {
            error(errors, fileName, "snapshot.bin is missing");
            return null;
        }
        return new Scanned(bytes, meta);
    }

    private static void error(List<String> errors, String fileName, String message) {
        errors.add(fileName + ": " + message);
    }

    /** 原始 {@code snapshot.bin} 字节与其解析结果：前者用于同 Epoch full 的逐字节比较。 */
    private record Scanned(byte[] bytes, ArchiveSnapshot meta) {
    }

    /** Epoch 升序的待导入归档；同一 Epoch 的 full 已去重。 */
    List<Entry> entries() {
        return entries;
    }

    /** 可继续导入但需要提示的情况。 */
    List<String> warnings() {
        return warnings;
    }

    /** 扫描阶段就已失败、无法导入的归档。 */
    List<String> errors() {
        return errors;
    }

    private static byte[] readSnapshotBytes(Path tar) throws IOException {
        try (InputStream input = Files.newInputStream(tar); TarReader reader = new TarReader(input)) {
            TarReader.Entry entry;
            while ((entry = reader.nextEntry()) != null) {
                byte[] data = reader.readEntryData();
                if (entry.name.equals("snapshot.bin")) return data;
            }
        }
        return null;
    }
}
