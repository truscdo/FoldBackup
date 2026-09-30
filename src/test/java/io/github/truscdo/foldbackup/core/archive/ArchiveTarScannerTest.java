package io.github.truscdo.foldbackup.core.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ArchiveTarScanner} 单元测试：tar 列表解析、Epoch 排序、diff 匹配过滤。
 * <p>
 * 自 {@code tui/ArchiveTuiTest} 迁移（逻辑从 ArchiveTui 下沉至 core/archive）。
 */
class ArchiveTarScannerTest {

    @TempDir
    Path tempDir;

    private void writeTar(Path tar, ArchiveSnapshot meta) throws IOException {
        try (var out = Files.newOutputStream(tar);
                TarWriter writer = new TarWriter(out)) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(meta));
        }
    }

    private ArchiveSnapshot full(long epoch, String sourceId) {
        ArchiveSnapshot m = new ArchiveSnapshot();
        m.type = "full";
        m.epoch = epoch;
        m.sourceSnapshotId = sourceId;
        m.regionCount = 2;
        m.chunkCount = 10;
        return m;
    }

    private ArchiveSnapshot diff(long epoch, long baseEpoch, String sourceId) {
        ArchiveSnapshot m = new ArchiveSnapshot();
        m.type = "diff";
        m.epoch = epoch;
        m.baseEpoch = baseEpoch;
        m.sourceSnapshotId = sourceId;
        m.regionCount = 1;
        m.chunkCount = 3;
        return m;
    }

    // ── scan ──

    @Test
    void scan_parsesTars_sortedNewestFirst() throws IOException {
        Path archive = Files.createDirectories(tempDir.resolve("archive"));
        writeTar(archive.resolve("S-20260825_120000[full].tar"), full(1724311200L, "S-a"));
        writeTar(archive.resolve("S-20260825_130000[full].tar"), full(1724313600L, "S-b"));
        writeTar(archive.resolve("S-20260825_120000[S-20260825_130000].tar"),
                diff(1724313600L, 1724311200L, "S-b"));
        Files.writeString(archive.resolve("readme.txt"), "not a tar");

        List<ArchiveTarScanner.ArchiveTarInfo> tars = ArchiveTarScanner.scan(archive);
        assertEquals(3, tars.size(), "only .tar files counted");
        // 最新 Epoch 在前；同一 Epoch 时按文件名倒序作稳定显示排序。
        assertEquals("S-20260825_130000[full].tar", tars.get(0).fileName());
        assertEquals("S-20260825_120000[S-20260825_130000].tar", tars.get(1).fileName());
        assertEquals("S-20260825_120000[full].tar", tars.get(2).fileName());
        // 元数据解析
        assertEquals("full", tars.get(0).meta().type);
        assertEquals("diff", tars.get(1).meta().type);
    }

    @Test
    void scan_missingDir_returnsEmpty() {
        assertTrue(ArchiveTarScanner.scan(tempDir.resolve("nope")).isEmpty());
    }

    @Test
    void scan_tarWithoutSnapshotJson_metaNull() throws IOException {
        Path archive = Files.createDirectories(tempDir.resolve("archive"));
        Files.writeString(archive.resolve("S-20260825_120000[full].tar"), "not a real tar");

        List<ArchiveTarScanner.ArchiveTarInfo> tars = ArchiveTarScanner.scan(archive);
        assertEquals(1, tars.size());
        assertNull(tars.get(0).meta(), "unparseable tar yields null meta");
    }

    // ── fulls ──

    @Test
    void fulls_filtersFullOnly() throws IOException {
        Path archive = Files.createDirectories(tempDir.resolve("archive"));
        writeTar(archive.resolve("S-20260825_120000[full].tar"), full(1724311200L, "S-a"));
        writeTar(archive.resolve("S-20260825_120000[S-20260825_130000].tar"),
                diff(1724313600L, 1724311200L, "S-b"));

        List<ArchiveTarScanner.ArchiveTarInfo> fulls = ArchiveTarScanner.fulls(ArchiveTarScanner.scan(archive));
        assertEquals(1, fulls.size());
        assertEquals("S-20260825_120000[full].tar", fulls.get(0).fileName());
    }

    @Test
    void epochLookupsUseMetadataAfterArchiveRename() throws IOException {
        Path archive = Files.createDirectories(tempDir.resolve("archive"));
        Path fullTar = archive.resolve("backup-full-renamed.tar");
        Path diffTar = archive.resolve("backup-diff-renamed.tar");
        writeTar(fullTar, full(1724311200L, "S-20260825_120000"));
        writeTar(diffTar, diff(1724313600L, 1724311200L, "S-20260825_130000"));

        List<ArchiveTarScanner.ArchiveTarInfo> tars = ArchiveTarScanner.scan(archive);
        assertEquals(fullTar, ArchiveTarScanner.fullAtEpoch(tars, 1724311200L));
        assertEquals(diffTar, ArchiveTarScanner.diffAtEpoch(tars, 1724311200L, 1724313600L));

    }

}
