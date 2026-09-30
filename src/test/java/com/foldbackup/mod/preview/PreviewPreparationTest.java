package com.foldbackup.mod.preview;

import com.foldbackup.core.backup.RegionProcessor;
import com.foldbackup.core.format.ChunkData;
import com.foldbackup.core.format.Region;
import com.foldbackup.core.rollback.ChunkRollbackSupport;
import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.rollback.PreviewSnapshotReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static com.foldbackup.core.format.EntityNbtFixture.chunk;
import static com.foldbackup.core.format.EntityNbtFixture.entity;

class PreviewPreparationTest {
    @TempDir
    Path temp;

    @Test
    void malformedEntitiesDoNotPreventReadingBlocks() throws Exception {
        var raw = readSnapshot(new byte[] { 10, 0 });
        assertArrayEquals(new byte[] { 10, 0, 0, 0 }, raw.blocks());
        assertNotNull(raw.entityError());
        assertTrue(raw.entityError().contains("历史实体读取失败"));
        assertNull(raw.entities());
    }

    @Test
    void missingEntitiesRemainAKnownEmptySet() throws Exception {
        var raw = readSnapshot(null);
        assertNull(raw.entityError());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.REGION_MISSING, raw.entities().state());
        assertTrue(raw.entities().snapshot().isEmpty());
    }

    @Test
    void validHistoricalEntitiesRemainAvailableForStatistics() throws Exception {
        UUID id = UUID.randomUUID();
        var raw = readSnapshot(chunk(0, 0, entity(id, 8, 8)));
        assertNull(raw.entityError());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.PRESENT, raw.entities().state());
        assertTrue(raw.entities().snapshot().entities().stream().anyMatch(e -> e.uuid().equals(id)));
    }

    private PreviewSnapshotReader.Snapshot readSnapshot(byte[] entities) throws Exception {
        Path world = temp.resolve("world");
        // 用一个空复合标签即可测试读取路径，无需依赖游戏注册表解码。
        writeRegion(world.resolve("region/r.0.0.mca"), new byte[] { 10, 0, 0, 0 });
        if (entities != null)
            writeRegion(world.resolve("entities/r.0.0.mca"), entities);
        var store = new SnapshotStore(temp.resolve("store"));
        var blobs = new BlobStore(temp.resolve("store"), 6);
        var scan = new RegionProcessor(store::getIndex, blobs, 6).scanWorld(world, null, null);
        assertTrue(scan.getErrors().isEmpty(), scan.getErrors().toString());
        var manifest = new SnapshotManifest("S-20260910_120000", 1789012800L, "world");
        manifest.setRegions(scan.getRegions());
        manifest.setExtraFiles(scan.getFiles());
        store.saveSnapshot(manifest);
        var raw = PreviewSnapshotReader.read(store, blobs, 0, 0, null, "");
        assertEquals(manifest.getSnapshotId(), raw.snapshotId());
        return raw;
    }

    private static void writeRegion(Path path, byte[] bytes) throws IOException {
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData(bytes, 0, 0));
        com.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(path, region, 6);
    }
}
