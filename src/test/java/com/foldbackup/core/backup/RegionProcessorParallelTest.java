package com.foldbackup.core.backup;

import com.foldbackup.core.store.RegionEntry;
import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.MetadataIndex;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.store.SnapshotManifest;
import com.foldbackup.core.format.ChunkData;
import com.foldbackup.core.format.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RegionProcessorParallelTest {

    @TempDir
    Path tempDir;

    @Test
    void reusedProcessorSeesCommittedAdditionsAndDeletions() throws Exception {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Path mca = world.resolve("region/r.0.0.mca");
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData("chunk".getBytes(StandardCharsets.UTF_8), 0, 0));
        region.setTimestamp(0, 1000);
        com.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region, 6);
        Path root = tempDir.resolve("backup");
        BlobStore blobs = new BlobStore(root, 6);
        SnapshotStore store = new SnapshotStore(root);
        RegionProcessor processor = new RegionProcessor(store::getIndex, blobs, 6);
        ScanResult initial = processor.scanWorld(world, null);
        assertTrue(initial.getErrors().isEmpty());
        SnapshotManifest snapshot = new SnapshotManifest("S-20260902_120000", 1000, "world");
        initial.populateManifest(snapshot);
        store.saveSnapshot(snapshot);
        String oldBlob = initial.getRegions().getFirst().getBlobPath();

        // Same region content reuses the content-addressed blob after source metadata
        // changes.
        com.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(mca, region, 0);
        ScanResult reused = processor.scanWorld(world, snapshot);
        assertTrue(reused.getErrors().isEmpty());
        assertEquals(initial.getRegions().getFirst().getContentHash(),
                reused.getRegions().getFirst().getContentHash());
        assertEquals(oldBlob, reused.getRegions().getFirst().getBlobPath());

        store.deleteSnapshot(snapshot.getEpochSeconds());
        assertFalse(Files.exists(root.resolve(oldBlob)));
        ScanResult recreated = processor.scanWorld(world, null);
        assertTrue(recreated.getErrors().isEmpty());
        assertEquals(oldBlob, recreated.getRegions().getFirst().getBlobPath());
        assertTrue(Files.exists(root.resolve(recreated.getRegions().getFirst().getBlobPath())));
    }

    @Test
    void parallelRegionScanMatchesSerialScan() throws Exception {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        for (int i = 0; i < 4; i++) {
            Region region = new Region(i, 0, 1000);
            region.setChunk(0, new ChunkData(
                    ("chunk-" + i).getBytes(StandardCharsets.UTF_8), i * 32, 0));
            region.setTimestamp(0, 1000 + i);
            com.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(
                    world.resolve("region/r." + i + ".0.mca"), region, 6);
        }

        ScanResult serial = scan(world, tempDir.resolve("serial"), 1);
        ScanResult parallel = scan(world, tempDir.resolve("parallel"), 4);

        assertIterableEquals(
                serial.getRegions().stream().map(RegionEntry::getFilename).toList(),
                parallel.getRegions().stream().map(RegionEntry::getFilename).toList());
        assertEquals(serial.getRegions().size(), parallel.getRegions().size());
        for (int i = 0; i < serial.getRegions().size(); i++) {
            RegionEntry expected = serial.getRegions().get(i);
            RegionEntry actual = parallel.getRegions().get(i);
            assertEquals(expected.getFilename(), actual.getFilename());
            assertEquals(expected.getContentHash(), actual.getContentHash());
            assertEquals(expected.getSourceMetadata(), actual.getSourceMetadata());
            assertEquals(expected.getSizeCompressed(), actual.getSizeCompressed());
            assertEquals(expected.getSizeUncompressed(), actual.getSizeUncompressed());
        }
        assertEquals(serial.getChangedRegions(), parallel.getChangedRegions());
        assertEquals(serial.getNewBytes(), parallel.getNewBytes());
        assertEquals(serial.getErrors(), parallel.getErrors());
    }

    private static ScanResult scan(Path world, Path store, int threads) throws Exception {
        BlobStore blobStore = new BlobStore(store, 6);
        RegionProcessor processor = new RegionProcessor(
                blobStore, 6, new MetadataIndex(), BackupFilter.defaults(), threads, 256);
        return processor.scanWorld(world, null);
    }
}
