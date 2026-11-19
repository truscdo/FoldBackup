package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 归档共享解析与基线报错工具。
 * <p>
 * 供 CLI、TUI 与校验/导入流程复用：
 * <ul>
 * <li>{@link #readSnapshot} — 读取 tar 内 {@code snapshot.bin}</li>
 * <li>{@link #expectedFullName} / {@link #baselineArchiveErrorMessage} — 归档基线与冲突消息</li>
 * </ul>
 */
public final class ArchiveFormatUtil {

    private ArchiveFormatUtil() {
    }

    /**
     * 读取 tar 内 {@code snapshot.bin} 并反序列化为 {@link ArchiveSnapshot}。
     *
     * @param tar 归档 tar 文件
     * @return 元数据；tar 缺失 / 无 snapshot.bin / 解析失败时返回 {@code null}
     */
    public static ArchiveSnapshot readSnapshot(Path tar) {
        try {
            byte[] data = readSnapshotBytes(tar);
            return data == null ? null : ArchiveSnapshotCodec.decode(data);
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    /** 读取 tar 内 {@code snapshot.bin} 的原始字节；缺失时返回 {@code null}。 */
    static byte[] readSnapshotBytes(Path tar) throws IOException {
        try (InputStream fis = Files.newInputStream(tar);
                TarReader reader = new TarReader(fis)) {
            TarReader.Entry e;
            while ((e = reader.nextEntry()) != null) {
                if (e.name.equals("snapshot.bin")) {
                    return reader.readEntryData();
                }
                reader.readEntryData();
            }
        }
        return null;
    }

    private static final String CONFLICTING_FULL_PREFIX = "Multiple conflicting full archives for epoch ";

    static String duplicateFullWarning(long epochSeconds, String keptFile, String ignoredFile) {
        return "duplicate full archive for epoch " + epochSeconds + ": keeping "
                + keptFile + ", ignoring " + ignoredFile;
    }

    static String conflictingFullMessage(long epochSeconds, List<String> fileNames) {
        return CONFLICTING_FULL_PREFIX + epochSeconds + ": " + String.join(", ", fileNames);
    }

    /** 判断异常是否由同 Epoch 多份完整归档内容冲突触发。 */
    public static boolean isConflictingFullError(IOException error) {
        return error != null && error.getMessage() != null
                && error.getMessage().startsWith(CONFLICTING_FULL_PREFIX);
    }

    /** 根据基线 Epoch 秒推导期望的完整归档文件名。 */
    public static String expectedFullName(long baseEpoch) {
        return ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(baseEpoch));
    }

    /** 构造差分归档缺失或同 Epoch 基线冲突时的统一提示信息。 */
    public static String baselineArchiveErrorMessage(long baseEpoch, IOException cause) {
        String expected = expectedFullName(baseEpoch);
        if (isConflictingFullError(cause)) {
            return "Required baseline archive " + expected + " is ambiguous: " + cause.getMessage();
        }
        return "Required baseline archive " + expected + " is missing";
    }
}
