package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 归档目录扫描：列出 {@code *.tar}、解析 snapshot.bin、区分 full/diff。
 * <p>
 * CLI 与 TUI 共用的领域查询逻辑（无控制台 IO）。
 * 扫描结果按归档元数据中的 Epoch 秒倒序。
 */
public final class ArchiveTarScanner {

    private ArchiveTarScanner() {
    }

    /** 归档根下的一个 tar 及其解析出的元数据。 */
    public record ArchiveTarInfo(Path tar, String fileName, ArchiveSnapshot meta, long size) {
    }

    /**
     * 扫描归档根下所有 {@code .tar}，解析 snapshot.bin，按 Epoch 秒倒序（最新在前）。
     * 目录不存在或为空时返回空列表。
     */
    public static List<ArchiveTarInfo> scan(Path archiveRoot) {
        List<ArchiveTarInfo> tars = new ArrayList<>();
        if (!Files.isDirectory(archiveRoot)) {
            return tars;
        }
        try (var stream = Files.list(archiveRoot)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".tar")) {
                    continue;
                }
                long size = 0;
                try {
                    size = Files.size(p);
                } catch (IOException ignored) {
                    // fall through
                }
                tars.add(new ArchiveTarInfo(p, name, ArchiveFormatUtil.readSnapshot(p), size));
            }
        } catch (IOException ignored) {
            // fall through
        }
        tars.sort(Comparator
                .comparingLong((ArchiveTarInfo info) -> info.meta() == null ? Long.MIN_VALUE : info.meta().epoch)
                .reversed()
                .thenComparing(ArchiveTarInfo::fileName, Comparator.reverseOrder()));
        return tars;
    }

    /** 过滤出 full 归档（保持输入顺序：文件名倒序，最新在前）。 */
    public static List<ArchiveTarInfo> fulls(List<ArchiveTarInfo> tars) {
        List<ArchiveTarInfo> fulls = new ArrayList<>();
        for (ArchiveTarInfo t : tars) {
            if (t.meta() != null && t.meta().isFull()) {
                fulls.add(t);
            }
        }
        return fulls;
    }

    /** Find the unique full archive whose metadata has the requested epoch. */
    public static Path fullAtEpoch(List<ArchiveTarInfo> tars, long epochSeconds) throws IOException {
        return uniqueTar(tars.stream().filter(info -> info.meta() != null && info.meta().isFull()
                && info.meta().epoch == epochSeconds).toList(), "full", epochSeconds);
    }

    /** Find a diff archive by target epoch and use the requested base when available. */
    public static Path diffAtEpoch(List<ArchiveTarInfo> tars, long baseEpochSeconds, long targetEpochSeconds)
            throws IOException {
        List<ArchiveTarInfo> targetMatches = tars.stream()
                .filter(info -> info.meta() != null && info.meta().isDiff()
                        && info.meta().epoch == targetEpochSeconds)
                .toList();
        List<ArchiveTarInfo> baseMatches = targetMatches.stream()
                .filter(info -> info.meta().baseEpoch == baseEpochSeconds)
                .toList();
        if (baseMatches.size() == 1) return baseMatches.getFirst().tar();
        if (baseMatches.size() > 1) return uniqueTar(baseMatches, "diff", targetEpochSeconds);
        // A uniquely identified target with a bad baseline should reach
        // ArchiveRestorePlan, which reports the baseline mismatch explicitly.
        return uniqueTar(targetMatches, "diff", targetEpochSeconds);
    }

    private static Path uniqueTar(List<ArchiveTarInfo> matches, String type, long epochSeconds) throws IOException {
        if (matches.isEmpty()) throw new IOException("No " + type + " archive for epoch: " + epochSeconds);
        if (matches.size() > 1) {
            throw new IOException("Multiple " + type + " archives for epoch " + epochSeconds + ": "
                    + matches.stream().map(ArchiveTarInfo::fileName).toList());
        }
        return matches.getFirst().tar();
    }

}
