package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkRollbackSupport} 的单元测试。
 * <p>
 * {@link ChunkRollbackSupport#resolveSnapshot} 和
 * {@link ChunkRollbackSupport#resolveChunk} 的测试需要真实的
 * {@link SnapshotStore}/{@link BlobStore} 及临时备份根目录；
 * 这些测试在 {@link ChunkRollbackCoordinatorTest} 中覆盖。
 */
class ChunkRollbackSupportTest {

    // ── findRegionEntry 测试 ──

    @Test
    void findRegionEntry_exactMatch() {
        var manifest = new SnapshotManifest();
        manifest.setRegions(List.of(
                entry("region/r.0.0.mca", "hash-a"),
                entry("region/r.-1.2.mca", "hash-b")));

        var result = ChunkRollbackSupport.findRegionEntry(manifest, "region/r.0.0.mca");
        assertNotNull(result);
        assertEquals("hash-a", result.getContentHash());
    }

    @Test
    void findRegionEntry_noMatch() {
        var manifest = new SnapshotManifest();
        manifest.setRegions(List.of(
                entry("region/r.0.0.mca", "hash-a")));

        var result = ChunkRollbackSupport.findRegionEntry(manifest, "region/r.1.1.mca");
        assertNull(result);
    }

    @Test
    void findRegionEntry_nullRegions() {
        var manifest = new SnapshotManifest();
        var result = ChunkRollbackSupport.findRegionEntry(manifest, "region/r.0.0.mca");
        assertNull(result);
    }

    // ── tryLockRegionFile 测试 ──

    @Test
    void tryLockRegionFile_fileDoesNotExist(@TempDir Path tmpDir) {
        Path missing = tmpDir.resolve("nonexistent.mca");
        assertTrue(ChunkRollbackSupport.tryLockRegionFile(missing),
                "Non-existent file should be considered unlockable (returns true)");
    }

    @Test
    void tryLockRegionFile_fileExistsAndUnlocked(@TempDir Path tmpDir) throws IOException {
        Path tmp = tmpDir.resolve("r.0.0.mca");
        Files.writeString(tmp, "dummy content");
        assertTrue(ChunkRollbackSupport.tryLockRegionFile(tmp),
                "File not held by another process should be lockable");
    }

    @Test
    void tryLockRegionFile_fileIsLocked(@TempDir Path tmpDir) throws IOException {
        Path tmp = tmpDir.resolve("r.0.0.mca");
        Files.writeString(tmp, "dummy content");

        // 打开并持有排他锁
        var channel = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.APPEND);
        var lock = channel.tryLock();
        assertNotNull(lock, "Must be able to acquire lock initially");

        try {
            assertFalse(ChunkRollbackSupport.tryLockRegionFile(tmp),
                    "File with held lock should return false");
        } finally {
            lock.release();
            channel.close();
        }
    }

    // ── 辅助方法 ──

    private static RegionEntry entry(String filename, String hash) {
        var e = new RegionEntry();
        e.setFilename(filename);
        e.setContentHash(hash);
        return e;
    }
}
