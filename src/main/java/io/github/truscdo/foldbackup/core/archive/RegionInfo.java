package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.store.SourceMetadata;

/** 基线中的一个 Region 条目：路径、内容身份、解压后大小与源元数据。 */
record RegionInfo(String filename, String contentHash, long sizeUncompressed, SourceMetadata metadata) {
    RegionInfo {
        metadata = metadata == null ? SourceMetadata.empty() : metadata;
    }
}
