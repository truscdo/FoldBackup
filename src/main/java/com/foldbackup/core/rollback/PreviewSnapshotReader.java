package com.foldbackup.core.rollback;

import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.SnapshotStore;

import javax.annotation.Nullable;
import java.io.IOException;

/** 在仓库锁保护下读取单个快照；实体统计属于可缺省的补充数据。 */
public final class PreviewSnapshotReader {
    private PreviewSnapshotReader() {}

    public record Snapshot(long epochSeconds, String snapshotId, byte[] blocks,
                           ChunkRollbackSupport.ResolvedEntitySnapshot entities, String entityError) {}

    public static Snapshot read(SnapshotStore store, BlobStore blobs, int x, int z,
                                @Nullable Long epochSeconds, String dimension) throws IOException {
        try (var ignored = store.beginWrite()) {
            var resolved = ChunkRollbackSupport.resolveChunk(store, blobs, x, z, epochSeconds, dimension);
            var chunk = resolved.chunk();
            if (chunk == null) throw new IOException("目标区块不存在于快照，不能按空气预览");
            byte[] blocks = chunk.getRawChunk();
            ChunkRollbackSupport.ResolvedEntitySnapshot entities;
            try {
                entities = ChunkRollbackSupport.resolveEntities(resolved.manifest(), blobs, x, z, dimension);
            } catch (Exception e) {
                return new Snapshot(resolved.manifest().getEpochSeconds(), resolved.manifest().getSnapshotId(),
                        blocks, null,
                        "历史实体读取失败: " + e.getMessage());
            }
            return new Snapshot(resolved.manifest().getEpochSeconds(), resolved.manifest().getSnapshotId(),
                    blocks, entities, null);
        }
    }
}
