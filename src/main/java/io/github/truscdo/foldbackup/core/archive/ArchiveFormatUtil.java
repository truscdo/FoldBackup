package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 归档共享解析/格式化工具。
 * <p>
 * 从 {@code cli/ArchiveCli} 的私有 static 方法提取，供 CLI 与 TUI 复用：
 * <ul>
 * <li>{@link #readSnapshot} — 读取 tar 内 {@code snapshot.bin}</li>
 * <li>{@link #formatEpoch} — epoch 秒 → {@code yyyy-MM-dd HH:mm:ss}（显示用）</li>
 * <li>{@link #formatSize} — 字节数 → B/K/M/G</li>
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
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    /** 格式化 epoch 秒为本地时区 {@code yyyy-MM-dd HH:mm:ss}。 */
    public static String formatEpoch(long epochSeconds) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epochSeconds * 1000));
    }

    /** 格式化字节数为人类可读的 B/K/M/G（无空格，如 {@code 1.5M}）。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        }
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }
}
