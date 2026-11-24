package io.github.truscdo.foldbackup.core.rollback;

import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.EntityNbtFixture;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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

    // ── POI 区域命名与缺失列 ──

    @Test
    void poiRegionFilename_usesPoiDirectory() {
        assertEquals("poi/r.0.0.mca", ChunkRollbackSupport.poiRegionFilename("", 0, 0));
        assertEquals("DIM-1/poi/r.-1.2.mca", ChunkRollbackSupport.poiRegionFilename("DIM-1/", -1, 2));
    }

    @Test
    void resolvePoiChunk_missingRegionReturnsNull(@TempDir Path tmpDir) throws IOException {
        var manifest = new SnapshotManifest();
        var blobs = new io.github.truscdo.foldbackup.core.store.BlobStore(tmpDir, 6);
        assertNull(ChunkRollbackSupport.resolvePoiChunk(manifest, blobs, 0, 0, ""));
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

    // ── RollbackScope 与实体计数预览 ──

    @Test
    void rollbackScopeParseAcceptsCaseInsensitiveAndRejectsInvalid() throws IOException {
        assertEquals(RollbackScope.ALL, RollbackScope.parse("all"));
        assertEquals(RollbackScope.BLOCKS, RollbackScope.parse("BLOCKS"));
        assertEquals(RollbackScope.ENTITIES, RollbackScope.parse(" Entities "));
        assertThrows(IOException.class, () -> RollbackScope.parse(""));
        assertThrows(IOException.class, () -> RollbackScope.parse(null));
        assertThrows(IOException.class, () -> RollbackScope.parse("poi"));
    }

    @Test
    void previewEntitiesCountsPassengersMissingColumnsAndCorruptedData(@TempDir Path tmpDir) throws Exception {
        Path world = tmpDir.resolve("world");
        Path storeDir = tmpDir.resolve("store");
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData(new byte[] {1, 2}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);

        byte[] twoEntities = EntityNbtFixture.chunk(0, 0,
                EntityNbtFixture.entity(UUID.randomUUID(), 8, 8, "minecraft:pig", 11, "",
                        EntityNbtFixture.entity(UUID.randomUUID(), 8, 8)));
        Region entities = new Region(0, 0, 1000);
        entities.setChunk(0, new ChunkData(twoEntities, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("entities/r.0.0.mca"), entities, 6);

        BlobStore blobs = new BlobStore(storeDir, 6);
        var scan = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        SnapshotManifest manifest = new SnapshotManifest("S-20260908_120000", 1000L, "world");
        scan.populateManifest(manifest);

        // 本地更改为 1 个实体，验证快照（含乘客共 2）与本地计数独立
        Region localEntities = new Region(0, 0, 2000);
        localEntities.setChunk(0, new ChunkData(
                EntityNbtFixture.chunk(0, 0, EntityNbtFixture.entity(UUID.randomUUID(), 8, 8)), 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("entities/r.0.0.mca"), localEntities, 6);

        var presentPreview = ChunkRollbackSupport.previewEntities(manifest, blobs, world, 0, 0, "");
        assertEquals(2L, presentPreview.snapshotCount());
        assertEquals(0, presentPreview.snapshotSkippedTrees());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.PRESENT, presentPreview.snapshotState());
        assertFalse(presentPreview.snapshotUnrecoverable());
        assertEquals(1L, presentPreview.currentCount());
        assertNull(presentPreview.currentError());

        // 目标槽位在快照中缺失实体列 -> 视为空（0）
        var missingChunkPreview = ChunkRollbackSupport.previewEntities(manifest, blobs, world, 1, 0, "");
        assertEquals(0L, missingChunkPreview.snapshotCount());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.CHUNK_MISSING, missingChunkPreview.snapshotState());
        assertFalse(missingChunkPreview.snapshotUnrecoverable());
        assertEquals(0L, missingChunkPreview.currentCount());

        // 快照无实体区域 -> 视为空（0）
        var emptyManifest = new SnapshotManifest("S-20260908_120001", 1001L, "world");
        var missingRegionPreview = ChunkRollbackSupport.previewEntities(emptyManifest, blobs, world, 0, 0, "");
        assertEquals(0L, missingRegionPreview.snapshotCount());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.REGION_MISSING, missingRegionPreview.snapshotState());
        assertFalse(missingRegionPreview.snapshotUnrecoverable());

        // 损坏快照实体 NBT -> 标记 snapshotUnrecoverable
        Region brokenEntities = new Region(0, 0, 3000);
        brokenEntities.setChunk(0, new ChunkData(new byte[] {10, 0}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("entities/r.0.0.mca"), brokenEntities, 6);
        var brokenScan = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        SnapshotManifest brokenManifest = new SnapshotManifest("S-20260908_120002", 1002L, "world");
        brokenScan.populateManifest(brokenManifest);

        var brokenPreview = ChunkRollbackSupport.previewEntities(brokenManifest, blobs, world, 0, 0, "");
        assertTrue(brokenPreview.snapshotUnrecoverable());
        assertNotNull(brokenPreview.snapshotError());
        assertNull(brokenPreview.currentCount());
        assertNotNull(brokenPreview.currentError());
    }

    @Test
    void previewEntitiesReportsSkippedTreesAndReadsSingleSlotWithoutTouchingCorruptedNeighbors(
            @TempDir Path tmpDir) throws Exception {
        Path world = tmpDir.resolve("world-isolated");
        Path storeDir = tmpDir.resolve("store-isolated");
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData(new byte[] {1, 2}, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("region/r.0.0.mca"), region, 6);

        // 快照包含 1 个合法实体与 1 个坐标越界实体（UUID 可解析 -> skippedTrees=1）
        byte[] mixedEntities = EntityNbtFixture.chunk(0, 0,
                EntityNbtFixture.entity(UUID.randomUUID(), 8, 8),
                EntityNbtFixture.entity(UUID.randomUUID(), 24, 8));
        Region entities = new Region(0, 0, 1000);
        entities.setChunk(0, new ChunkData(mixedEntities, 0, 0));
        AnvilTestSupport.writeRegionAnvil(world.resolve("entities/r.0.0.mca"), entities, 6);

        BlobStore blobs = new BlobStore(storeDir, 6);
        var scan = new RegionProcessor(blobs, 6, new MetadataIndex()).scanWorld(world, null);
        SnapshotManifest manifest = new SnapshotManifest("S-20260908_120003", 1003L, "world");
        scan.populateManifest(manifest);

        // 本地实体区域含槽位 0 与槽位 1，随后将槽位 1 的压缩类型字节破坏为未实现的 99
        Region localEntities = new Region(0, 0, 2000);
        localEntities.setChunk(0, new ChunkData(
                EntityNbtFixture.chunk(0, 0, EntityNbtFixture.entity(UUID.randomUUID(), 8, 8)), 0, 0));
        localEntities.setChunk(1, new ChunkData(
                EntityNbtFixture.chunk(1, 0, EntityNbtFixture.entity(UUID.randomUUID(), 24, 8)), 1, 0));
        Path localEntityFile = world.resolve("entities/r.0.0.mca");
        AnvilTestSupport.writeRegionAnvil(localEntityFile, localEntities, 6);
        byte[] rawMca = Files.readAllBytes(localEntityFile);
        int slot1Loc = ((rawMca[4] & 0xFF) << 24)
                | ((rawMca[5] & 0xFF) << 16)
                | ((rawMca[6] & 0xFF) << 8)
                | (rawMca[7] & 0xFF);
        int slot1StartSector = slot1Loc >>> 8;
        assertTrue(slot1StartSector >= 2);
        rawMca[slot1StartSector * 4096 + 4] = 99;
        Files.write(localEntityFile, rawMca);

        var preview = ChunkRollbackSupport.previewEntities(manifest, blobs, world, 0, 0, "");
        assertEquals(1L, preview.snapshotCount());
        assertEquals(1, preview.snapshotSkippedTrees());
        assertFalse(preview.snapshotUnrecoverable());
        assertEquals(1L, preview.currentCount());
        assertNull(preview.currentError());

        var neighborPreview = ChunkRollbackSupport.previewEntities(manifest, blobs, world, 1, 0, "");
        assertNull(neighborPreview.currentCount());
        assertNotNull(neighborPreview.currentError());
    }

    // ── 辅助方法 ──

    private static RegionEntry entry(String filename, String hash) {
        var e = new RegionEntry();
        e.setFilename(filename);
        e.setContentHash(hash);
        return e;
    }
}
