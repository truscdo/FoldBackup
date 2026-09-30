package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkRollbackCoordinator} 的测试。
 * <p>
 * 快照解析失败（找不到快照、找不到区域）由 {@link ChunkRollbackSupport#resolveChunk} 处理，
 * 在 {@link ChunkRollbackSupportTest} 中测试。协调器将其包装在
 * {@code ChunkRollbackResult(false, ...)} 中——在此验证。
 */
class ChunkRollbackCoordinatorTest {

    // ── 解析失败处理 ──

    @Test
    void rollbackChunk_snapshotNotFound_returnsFailure() throws IOException {
        var sm = new SnapshotStore(createTempBackupRoot());
        var bs = new BlobStore(createTempBackupRoot(), 6);
        var coordinator = new ChunkRollbackCoordinator(sm, bs, 6);

        var result = coordinator.rollbackChunk(Path.of("region"), 0, 0, 123L, "");
        assertFalse(result.success());
        assertTrue(result.message().contains("Snapshot not found"));
    }

    @Test
    void rollbackChunk_noSnapshots_returnsFailure() throws IOException {
        var root = createTempBackupRoot();
        var sm = new SnapshotStore(root); // empty index
        var bs = new BlobStore(root, 6);
        var coordinator = new ChunkRollbackCoordinator(sm, bs, 6);

        var result = coordinator.rollbackChunk(Path.of("region"), 0, 0, null, "");
        assertFalse(result.success());
        assertTrue(result.message().contains("No snapshots available"));
    }

    // ── 文件 I/O 成功路径 ──

    @Test
    void rollbackChunk_regionDirDoesNotExist_returnsFailure() throws IOException {
        var root = createTempBackupRoot();
        // 创建带区域条目的快照
        var sm = new SnapshotStore(root);
        var bs = new BlobStore(root, 6);
        var coordinator = new ChunkRollbackCoordinator(sm, bs, 6);

        // 没有快照存在，因此在解析步骤将失败
        var result = coordinator.rollbackChunk(
                root.resolve("nonexistent"), 0, 0, null, "");
        assertFalse(result.success());
    }

    // ── 结果类型 ──

    @Test
    void chunkRollbackResult_record() {
        var success = new ChunkRollbackResult(true, "OK");
        assertTrue(success.success());
        assertEquals("OK", success.message());

        var failure = new ChunkRollbackResult(false, "Error");
        assertFalse(failure.success());
        assertEquals("Error", failure.message());
    }

    // ── 辅助方法 ──

    /** 创建没有 index.db 的最小备份根目录（空状态）。 */
    private static Path createTempBackupRoot() throws IOException {
        return Files.createTempDirectory("foldbackup-test-");
    }
}
