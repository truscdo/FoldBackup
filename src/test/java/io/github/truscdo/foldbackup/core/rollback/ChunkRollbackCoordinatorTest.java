package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @TempDir
    Path tempRoot;

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

    @Test
    void rollbackChunk_resolvedEmptyChunk_returnsFailure() throws IOException {
        var root = createTempBackupRoot();
        var sm = new SnapshotStore(root);
        var bs = new BlobStore(root, 6);
        var coordinator = new ChunkRollbackCoordinator(sm, bs, 6);
        var manifest = new SnapshotManifest("S-20260908_120000", 1000L, "world");
        var resolved = new ChunkRollbackSupport.ResolvedSnapshot(manifest, new RegionEntry(), null);

        var result = coordinator.rollbackChunk(root.resolve("world/region"), 0, 0, "", resolved);
        assertFalse(result.success());
        assertTrue(result.message().contains("is empty in snapshot"), result.message());
    }

    @Test
    void rollbackChunk_withPreResolvedChunk_doesNotReReadBlockBlob() throws IOException {
        var root = createTempBackupRoot();
        Path world = tempRoot.resolve("world");
        Path regionDir = world.resolve("region");
        byte[] oldChunk = "chunk-v1".getBytes(StandardCharsets.UTF_8);
        Region v1 = new Region(0, 0, 1000);
        v1.setChunk(0, new ChunkData(oldChunk, 0, 0));
        AnvilTestSupport.writeRegionAnvil(regionDir.resolve("r.0.0.mca"), v1, 6);

        var sm = new SnapshotStore(root);
        var bs = new BlobStore(root, 6);
        var scan = new RegionProcessor(bs, 6, new MetadataIndex()).scanWorld(world, null);
        var manifest = new SnapshotManifest("S-20260908_120000", 1000L, "world");
        scan.populateManifest(manifest);
        sm.saveSnapshot(manifest);

        Region v2 = new Region(0, 0, 2000);
        v2.setChunk(0, new ChunkData("chunk-v2".getBytes(StandardCharsets.UTF_8), 0, 0));
        AnvilTestSupport.writeRegionAnvil(regionDir.resolve("r.0.0.mca"), v2, 6);

        var resolved = ChunkRollbackSupport.resolveChunk(sm, bs, 0, 0, 1000L, "");
        assertNotNull(resolved.chunk());
        // 删除原区块 .lrh blob，证明传入已解析快照不会再次流式读取与校验该 blob
        Files.delete(bs.getRootDir().resolve(resolved.regionEntry().getBlobPath()));

        var coordinator = new ChunkRollbackCoordinator(sm, bs, 6);
        var result = coordinator.rollbackChunk(regionDir, 0, 0, "", resolved);
        assertTrue(result.success(), result.message());
        assertArrayEquals(oldChunk, AnvilTestSupport.readRegionAnvil(regionDir.resolve("r.0.0.mca"))
                .getChunk(0).getRawChunk());
    }

    @Test
    void rollbackChunk_mismatchedResolvedCoordinates_throws() throws IOException {
        var root = createTempBackupRoot();
        var coordinator = new ChunkRollbackCoordinator(new SnapshotStore(root), new BlobStore(root, 6), 6);
        var manifest = new SnapshotManifest("S-20260908_120000", 1000L, "world");
        var resolved = new ChunkRollbackSupport.ResolvedSnapshot(
                manifest, new RegionEntry(), new ChunkData(new byte[] {1}, 1, 0));

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.rollbackChunk(root.resolve("world/region"), 0, 0, "", resolved));
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
    private Path createTempBackupRoot() throws IOException {
        return Files.createTempDirectory(tempRoot, "backup-");
    }
}
