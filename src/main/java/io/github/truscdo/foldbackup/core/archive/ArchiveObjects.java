package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 归档内部的小工具：内容对象路径解析与源元数据归一化。
 * <p>
 * 同一条格式规则此前写在两处（{@link ArchiveBaseline} 与 {@link ArchiveRestorePlan} 各解析一次
 * {@code objects/regions|x.y.z} 身份），归一化写在三处；两者都收到这里，避免规则改一次漏一处。
 * 包内可见，不扩大公共 API。
 */
final class ArchiveObjects {

    private static final Pattern CONTENT_IDENTITY = Pattern.compile("[0-9a-f]{32}");

    private ArchiveObjects() {
    }

    /**
     * 从 {@code prefix + hash + suffix} 形式的条目名取出内容身份。
     *
     * @throws IOException 名称不符合内容对象路径格式，或身份不是 32 位小写十六进制
     */
    static String hashOf(String name, String prefix, String suffix) throws IOException {
        if (!name.startsWith(prefix) || !name.endsWith(suffix))
            throw new IOException("Invalid content object path: " + name);
        String hash = name.substring(prefix.length(), name.length() - suffix.length());
        if (!CONTENT_IDENTITY.matcher(hash).matches())
            throw new IOException("Invalid content object identity: " + name);
        return hash;
    }

    /** {@code null} 元数据按空处理，与 {@code RegionInfo} / {@code FileInfo} 的约定一致。 */
    static SourceMetadata metadata(SourceMetadata value) {
        return value == null ? SourceMetadata.empty() : value;
    }
}
