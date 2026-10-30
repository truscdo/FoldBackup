package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;

/** 基线中的一个普通文件条目：路径、内容身份、解压后大小与源元数据。 */
record FileInfo(String filename, String sourceHash, long sizeUncompressed, SourceMetadata metadata) {
    FileInfo {
        metadata = metadata == null ? SourceMetadata.empty() : metadata;
    }
}
