package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.MetadataIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RegionProcessor} 扫描的过滤集成测试——用临时目录模拟世界结构，
 * 验证过滤文件/目录被排除、未命中文件保留、0 字节文件无条件跳过。
 */
class RegionProcessorScanTest {

    @TempDir
    Path tempDir;

    private Set<String> scanFiles(Path world, BackupFilter filter) throws IOException {
        Path store = tempDir.resolve("store");
        BlobStore blobStore = new BlobStore(store, 6);
        RegionProcessor processor = new RegionProcessor(blobStore, 6, new MetadataIndex(), filter);
        ScanResult result = processor.scanWorld(world, null);
        return result.getFiles().stream()
                .map(FileEntry::getFilename)
                .collect(Collectors.toSet());
    }

    @Test
    void defaultFilter_excludesKnownNamesAndWip() throws IOException {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world);
        Files.createDirectories(world.resolve("data"));
        Files.createDirectories(world.resolve("data/backups"));
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.writeString(world.resolve("foo.wip"), "partial");
        Files.writeString(world.resolve("data/backups/x.bin"), "boom-cache");
        Files.writeString(world.resolve("data/foo.dat"), "keep me");
        Files.writeString(world.resolve("level.dat"), "world data");
        Files.createFile(world.resolve("data/empty.bin")); // 0 字节 → 跳过
        Files.writeString(world.resolve("chunk.mcc"), "mcc data"); // 不独立备份

        Set<String> files = scanFiles(world, BackupFilter.defaults());

        assertEquals(Set.of("data/foo.dat", "level.dat"), files,
                "session.lock / .wip / backups subtree excluded; .mcc not standalone; 0-byte skipped");
    }

    @Test
    void customFilter_excludesRegionFiles() throws IOException {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "not a real region");
        Files.writeString(world.resolve("level.dat"), "world data");

        Path store = tempDir.resolve("store");
        BlobStore blobStore = new BlobStore(store, 6);
        RegionProcessor processor = new RegionProcessor(blobStore, 6, new MetadataIndex(),
                new BackupFilter(List.of("**/*.mca")));
        ScanResult result = processor.scanWorld(world, null);

        assertTrue(result.getRegions().isEmpty(), "region files must be filtered out");
        assertTrue(result.getErrors().isEmpty(), "filtered-out region must not be parsed");
        assertEquals(Set.of("level.dat"),
                result.getFiles().stream().map(FileEntry::getFilename).collect(Collectors.toSet()));
    }

    @Test
    void customFilter_directoryPatternSkipsSubtree() throws IOException {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world.resolve("data/snapshots/sub"));
        Files.writeString(world.resolve("data/snapshots/a.bin"), "noise");
        Files.writeString(world.resolve("data/snapshots/sub/b.bin"), "more noise");
        Files.writeString(world.resolve("data/keep.txt"), "keep");
        Files.writeString(world.resolve("level.dat"), "world data");

        Set<String> files = scanFiles(world, new BackupFilter(List.of("**/snapshots")));

        assertEquals(Set.of("data/keep.txt", "level.dat"), files,
                "matching directory's whole subtree must be skipped");
    }
}
