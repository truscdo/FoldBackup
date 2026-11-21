package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次扫描得到的归档库视图：每个 tar 及其 {@code snapshot.bin}，按 Epoch 升序，full 已按 Epoch 去重。
 * <p>
 * 扫描避免全目录反复遍历与按 diff 重扫；差异归档只依赖在线快照不重开 full 归档，
 * 但导入 full 归档本身时仍会通过 {@link ArchiveBaseline} 读取其对象索引与 {@code snapshot.bin}。
 * <p>
 * 同 Epoch 多份 full 归档：{@code snapshot.bin} 字节一致时按文件名排序保留第一份并给出警告；
 * 不一致时记为错误并列出该 Epoch 全部冲突文件，整个 Epoch（含依赖它的 diff）不导入。
 * 导入顺序依赖 Epoch 升序，因此 full 必先于其 diff 处理。
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
        var errors = new ArchiveErrorCollector();
        List<String> warnings = new ArrayList<>();
        List<Path> tars = ArchiveTarScanner.listTarFiles(root);

        Map<Long, List<ScannedEntry>> fullGroups = new LinkedHashMap<>();
        List<Entry> nonFullEntries = new ArrayList<>();
        for (Path tar : tars) {
            String fileName = tar.getFileName().toString();
            Scanned scanned = readArchive(tar, fileName, errors);
            if (scanned == null) continue;
            Entry entry = new Entry(tar, scanned.meta());
            if (scanned.meta().isFull()) {
                fullGroups.computeIfAbsent(scanned.meta().epoch, ignored -> new ArrayList<>())
                        .add(new ScannedEntry(entry, scanned.bytes()));
            } else {
                nonFullEntries.add(entry);
            }
        }

        List<Entry> entries = new ArrayList<>();
        Map<Long, List<String>> conflictedFulls = new HashMap<>();
        for (var groupEntry : fullGroups.entrySet()) {
            long epoch = groupEntry.getKey();
            List<ScannedEntry> group = groupEntry.getValue();
            ScannedEntry first = group.getFirst();
            boolean allEqual = true;
            for (int i = 1; i < group.size(); i++) {
                if (!Arrays.equals(first.bytes(), group.get(i).bytes())) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) {
                entries.add(first.entry());
                for (int i = 1; i < group.size(); i++) {
                    warnings.add(ArchiveFormatUtil.duplicateFullWarning(
                            epoch, first.entry().fileName(), group.get(i).entry().fileName()));
                }
            } else {
                List<String> names = group.stream().map(item -> item.entry().fileName()).toList();
                conflictedFulls.put(epoch, names);
                errors.addRaw(ArchiveFormatUtil.conflictingFullMessage(epoch, names) + " (skipping epoch)");
            }
        }

        for (Entry entry : nonFullEntries) {
            ArchiveSnapshot meta = entry.meta();
            if (meta.isDiff() && conflictedFulls.containsKey(meta.baseEpoch)) {
                List<String> names = conflictedFulls.get(meta.baseEpoch);
                errors.add(entry.fileName(), "baseline epoch " + meta.baseEpoch
                        + " skipped due to conflicting full archives: " + String.join(", ", names));
            } else if (conflictedFulls.containsKey(meta.epoch)) {
                List<String> names = conflictedFulls.get(meta.epoch);
                errors.add(entry.fileName(), "epoch " + meta.epoch
                        + " skipped due to conflicting full archives: " + String.join(", ", names));
            } else {
                entries.add(entry);
            }
        }

        entries.sort(Comparator.comparingLong((Entry entry) -> entry.meta().epoch)
                .thenComparing(Entry::fileName));
        return new ArchiveLibrary(entries, warnings, errors.list());
    }

    /**
     * 读取并解析一个归档的 {@code snapshot.bin}；不可导入时把原因记入 {@code errors} 并返回
     * {@code null}，扫描继续处理下一个归档。
     */
    private static Scanned readArchive(Path tar, String fileName, ArchiveErrorCollector errors) {
        byte[] bytes;
        try {
            bytes = ArchiveFormatUtil.readSnapshotBytes(tar);
        } catch (IOException | RuntimeException e) {
            errors.add(fileName, e.getMessage());
            return null;
        }
        if (bytes == null) {
            errors.add(fileName, "snapshot.bin is missing");
            return null;
        }
        ArchiveSnapshot meta;
        try {
            meta = ArchiveSnapshotCodec.decode(bytes);
        } catch (IOException | RuntimeException e) {
            errors.add(fileName, e.getMessage());
            return null;
        }
        if (meta == null) {
            errors.add(fileName, "snapshot.bin is missing");
            return null;
        }
        return new Scanned(bytes, meta);
    }

    /** 原始 {@code snapshot.bin} 字节与其解析结果：前者用于同 Epoch full 的逐字节比较。 */
    private record Scanned(byte[] bytes, ArchiveSnapshot meta) {
    }

    private record ScannedEntry(Entry entry, byte[] bytes) {
    }

    /** Epoch 升序的待导入归档；同一 Epoch 的 full 已去重，冲突 Epoch 已移除。 */
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
}
