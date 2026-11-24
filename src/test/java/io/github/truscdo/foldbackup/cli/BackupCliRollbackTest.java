package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.format.AnvilTestSupport;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.EntityNbtFixture;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackCoordinator;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CLI rollback and subcommand dispatch infrastructure.
 */
class BackupCliRollbackTest {

        @TempDir
        Path temp;

        @Test
        void rollbackUsesChunkRollbackCoordinator() {
                var constructors = ChunkRollbackCoordinator.class.getConstructors();
                assertTrue(constructors.length > 0, "Coordinator must have public constructors");
                // 验证构造器签名：(SnapshotStore, BlobStore, int)
                var params = constructors[0].getParameterTypes();
                assertEquals(3, params.length, "Coordinator constructor should take 3 params");
        }

        @Test
        void rollbackUsesChunkRollbackSupport_tryLock() {
                // tryLockRegionFile 是公共静态方法——验证其可访问性
                try {
                        var method = ChunkRollbackSupport.class.getMethod("tryLockRegionFile", Path.class);
                        assertNotNull(method);
                        assertEquals(boolean.class, method.getReturnType());
                } catch (NoSuchMethodException e) {
                        fail("tryLockRegionFile(Path) must exist as public static: " + e.getMessage());
                }
        }

        @Test
        void rollbackUsesChunkRollbackSupport_resolveChunk() {
                try {
                        var method = ChunkRollbackSupport.class.getMethod("resolveChunk",
                                        SnapshotStore.class,
                                        io.github.truscdo.foldbackup.core.store.BlobStore.class,
                                        int.class, int.class, Long.class, String.class);
                        assertNotNull(method);
                        assertEquals(ChunkRollbackSupport.ResolvedSnapshot.class, method.getReturnType());
                } catch (NoSuchMethodException e) {
                        fail("resolveChunk(...) must exist as public static: " + e.getMessage());
                }
        }

        @Test
        void cliSnapshotCreateListRollbackAndDeleteRoundTrip() throws Exception {
                Path world = temp.resolve("world");
                Files.createDirectories(world);
                Files.writeString(world.resolve("level.dat"), "level-v1");

                byte[] block0Before = "block-0-v1".getBytes(StandardCharsets.UTF_8);
                byte[] block1Keep = "block-1-keep".getBytes(StandardCharsets.UTF_8);
                writeTwoChunks(world.resolve("region/r.0.0.mca"), block0Before, block1Keep);

                byte[] entity0Before = EntityNbtFixture.chunk(0, 0, EntityNbtFixture.entity(UUID.randomUUID(), 8, 8));
                byte[] entity1Keep = EntityNbtFixture.chunk(1, 0, EntityNbtFixture.entity(UUID.randomUUID(), 24, 8));
                writeTwoChunks(world.resolve("entities/r.0.0.mca"), entity0Before, entity1Keep);

                byte[] poi0Before = "poi-0-v1".getBytes(StandardCharsets.UTF_8);
                byte[] poi1Keep = "poi-1-keep".getBytes(StandardCharsets.UTF_8);
                writeTwoChunks(world.resolve("poi/r.0.0.mca"), poi0Before, poi1Keep);

                Path storeDir = temp.resolve("snapshots");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");

                SnapshotManifest created = new SnapshotStore(storeDir).loadLatestSnapshot();
                assertNotNull(created);
                String snap1 = created.getSnapshotId();

                String listOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "list"));
                assertTrue(listOut.contains(snap1), listOut);

                // 修改目标 chunk [0, 0]，保留邻居 chunk [1, 0]
                writeTwoChunks(world.resolve("region/r.0.0.mca"),
                                "block-0-v2".getBytes(StandardCharsets.UTF_8), block1Keep);
                writeTwoChunks(world.resolve("entities/r.0.0.mca"),
                                EntityNbtFixture.chunk(0, 0, EntityNbtFixture.entity(UUID.randomUUID(), 8, 8)),
                                entity1Keep);
                writeTwoChunks(world.resolve("poi/r.0.0.mca"),
                                "poi-0-v2".getBytes(StandardCharsets.UTF_8), poi1Keep);

                // 空区块必须在提示确认前被拒绝
                IOException emptyChunkErr = assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(), "--chunk", "5", "5"));
                assertTrue(emptyChunkErr.getMessage().contains("Requested chunk is empty in snapshot"),
                                emptyChunkErr.getMessage());

                // 非交互环境省略 --yes 必须被拒绝
                IOException rollbackConfirmErr = assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(), "--chunk", "0", "0"));
                assertTrue(rollbackConfirmErr.getMessage().contains("pass --yes to confirm"),
                                rollbackConfirmErr.getMessage());

                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(), "--chunk", "0", "0",
                                "--yes");

                assertArrayEquals(block0Before, readChunk(world.resolve("region/r.0.0.mca"), 0));
                assertArrayEquals(block1Keep, readChunk(world.resolve("region/r.0.0.mca"), 1));
                assertArrayEquals(entity0Before, readChunk(world.resolve("entities/r.0.0.mca"), 0));
                assertArrayEquals(entity1Keep, readChunk(world.resolve("entities/r.0.0.mca"), 1));
                assertArrayEquals(poi0Before, readChunk(world.resolve("poi/r.0.0.mca"), 0));
                assertArrayEquals(poi1Keep, readChunk(world.resolve("poi/r.0.0.mca"), 1));

                IOException deleteConfirmErr = assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "delete", snap1));
                assertTrue(deleteConfirmErr.getMessage().contains("pass --yes to confirm"),
                                deleteConfirmErr.getMessage());

                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "delete", snap1, "--yes");
                assertTrue(new SnapshotStore(storeDir).listSnapshots().isEmpty());
        }

        @Test
        void cliSnapshotRollbackScopeBlocksEntitiesAndValidation() throws Exception {
                Path world = temp.resolve("world-scope");
                Files.createDirectories(world);
                Files.writeString(world.resolve("level.dat"), "level-v1");

                byte[] block0Before = "block-0-v1".getBytes(StandardCharsets.UTF_8);
                byte[] block1Keep = "block-1-keep".getBytes(StandardCharsets.UTF_8);
                writeTwoChunks(world.resolve("region/r.0.0.mca"), block0Before, block1Keep);

                byte[] entity0Before = EntityNbtFixture.chunk(0, 0, EntityNbtFixture.entity(UUID.randomUUID(), 8, 8));
                byte[] entity1Keep = EntityNbtFixture.chunk(1, 0, EntityNbtFixture.entity(UUID.randomUUID(), 24, 8));
                writeTwoChunks(world.resolve("entities/r.0.0.mca"), entity0Before, entity1Keep);

                byte[] poi0Before = "poi-0-v1".getBytes(StandardCharsets.UTF_8);
                writeTwoChunks(world.resolve("poi/r.0.0.mca"), poi0Before, "poi-1-keep".getBytes(StandardCharsets.UTF_8));

                Path storeDir = temp.resolve("snapshots-scope");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");
                String snap1 = new SnapshotStore(storeDir).loadLatestSnapshot().getSnapshotId();

                // 修改区块 0 的方块、实体与 POI
                byte[] block0After = "block-0-v2".getBytes(StandardCharsets.UTF_8);
                byte[] entity0After = EntityNbtFixture.chunk(0, 0,
                                EntityNbtFixture.entity(UUID.randomUUID(), 8, 8),
                                EntityNbtFixture.entity(UUID.randomUUID(), 9, 9));
                byte[] poi0After = "poi-0-v2".getBytes(StandardCharsets.UTF_8);
                writeTwoChunks(world.resolve("region/r.0.0.mca"), block0After, block1Keep);
                writeTwoChunks(world.resolve("entities/r.0.0.mca"), entity0After, entity1Keep);
                writeTwoChunks(world.resolve("poi/r.0.0.mca"), poi0After, "poi-1-keep".getBytes(StandardCharsets.UTF_8));

                // 1) --scope blocks：仅还原方块与 POI，保留实体
                String blocksOut = captureStdout(() -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope", "blocks", "--yes"));
                assertTrue(blocksOut.contains("Excludes:  entities"), blocksOut);
                assertFalse(blocksOut.contains("  Entities:"), blocksOut);
                assertArrayEquals(block0Before, readChunk(world.resolve("region/r.0.0.mca"), 0));
                assertArrayEquals(poi0Before, readChunk(world.resolve("poi/r.0.0.mca"), 0));
                assertArrayEquals(entity0After, readChunk(world.resolve("entities/r.0.0.mca"), 0));

                // 重新改回方块与 POI 后测试 --scope entities：仅还原实体，保留新方块与 POI，并打印实体计数
                writeTwoChunks(world.resolve("region/r.0.0.mca"), block0After, block1Keep);
                writeTwoChunks(world.resolve("poi/r.0.0.mca"), poi0After, "poi-1-keep".getBytes(StandardCharsets.UTF_8));
                String entitiesOut = captureStdout(() -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope", "entities", "--yes"));
                assertTrue(entitiesOut.contains("Scope:     entity data only"), entitiesOut);
                assertTrue(entitiesOut.contains("Entities:  1 in snapshot; 2 currently in chunk"), entitiesOut);
                assertArrayEquals(entity0Before, readChunk(world.resolve("entities/r.0.0.mca"), 0));
                assertArrayEquals(entity1Keep, readChunk(world.resolve("entities/r.0.0.mca"), 1));
                assertArrayEquals(block0After, readChunk(world.resolve("region/r.0.0.mca"), 0));
                assertArrayEquals(poi0After, readChunk(world.resolve("poi/r.0.0.mca"), 0));

                // 2) 非法/重复/缺值 --scope 检查
                assertThrows(IOException.class, () -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope", "invalid", "--yes"));
                assertThrows(IOException.class, () -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope"));
                assertThrows(IOException.class, () -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snap1, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope", "blocks", "--scope", "entities", "--yes"));

                // 3) --scope entities 下若快照实体损坏，确认前即报错（不提示 confirm）
                Region brokenEntities = new Region(0, 0, System.currentTimeMillis());
                brokenEntities.setChunk(0, new ChunkData(new byte[] {10, 0}, 0, 0));
                AnvilTestSupport.writeRegionAnvil(world.resolve("entities/r.0.0.mca"), brokenEntities, 6);
                long prevEpoch = new SnapshotStore(storeDir).loadLatestSnapshot().getEpochSeconds();
                long deadline = System.currentTimeMillis() + 3000;
                while (Instant.now().getEpochSecond() <= prevEpoch && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                }
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");
                String brokenSnap = new SnapshotStore(storeDir).loadLatestSnapshot().getSnapshotId();
                IOException brokenErr = assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", brokenSnap, "--world", world.toString(),
                                "--chunk", "0", "0", "--scope", "entities"));
                assertTrue(brokenErr.getMessage().contains("Historical entities cannot be recovered"),
                                brokenErr.getMessage());
        }

        @Test
        void cliEntitiesScopeWorksWithoutRegionDirAndPrintsSkippedCount() throws Exception {
                Path sourceWorld = temp.resolve("source-world-entities-only");
                Files.createDirectories(sourceWorld);
                Files.writeString(sourceWorld.resolve("level.dat"), "level-v1");
                writeTwoChunks(sourceWorld.resolve("region/r.0.0.mca"),
                                "block-0".getBytes(StandardCharsets.UTF_8),
                                "block-1".getBytes(StandardCharsets.UTF_8));

                byte[] validEntity = EntityNbtFixture.entity(UUID.randomUUID(), 8, 8);
                byte[] outOfChunkEntity = EntityNbtFixture.entity(UUID.randomUUID(), 24, 8);
                byte[] snapshotEntities = EntityNbtFixture.chunk(0, 0, validEntity, outOfChunkEntity);
                writeTwoChunks(sourceWorld.resolve("entities/r.0.0.mca"), snapshotEntities,
                                EntityNbtFixture.chunk(1, 0));

                Path storeDir = temp.resolve("snapshots-entities-no-region");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", sourceWorld.toString(), "--threads", "1");
                String snapId = new SnapshotStore(storeDir).loadLatestSnapshot().getSnapshotId();

                // 目标维度仅包含 level.dat 与 entities/，不存在 region/ 目录
                Path targetWorld = temp.resolve("target-world-no-region");
                Files.createDirectories(targetWorld);
                Files.writeString(targetWorld.resolve("level.dat"), "level-v1");
                writeTwoChunks(targetWorld.resolve("entities/r.0.0.mca"),
                                EntityNbtFixture.chunk(0, 0,
                                                EntityNbtFixture.entity(UUID.randomUUID(), 8, 8),
                                                EntityNbtFixture.entity(UUID.randomUUID(), 9, 9)),
                                EntityNbtFixture.chunk(1, 0));

                String out = captureStdout(() -> BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "rollback", snapId, "--world", targetWorld.toString(),
                                "--chunk", "0", "0", "--scope", "entities", "--yes"));
                assertTrue(out.contains("Entities:  1 in snapshot (1 skipped); 2 currently in chunk"), out);
                assertFalse(Files.exists(targetWorld.resolve("region")));
                assertArrayEquals(EntityNbtFixture.chunk(0, 0, validEntity),
                                readChunk(targetWorld.resolve("entities/r.0.0.mca"), 0));
        }

        @Test
        void cliSnapshotExportAndArchiveDiffFlow() throws Exception {
                Path world = temp.resolve("world-diff");
                Files.createDirectories(world.resolve("data"));
                Files.writeString(world.resolve("level.dat"), "level-v1");
                Files.writeString(world.resolve("data/keep.txt"), "keep-v1");
                writeTwoChunks(world.resolve("region/r.0.0.mca"),
                                "chunk-0-v1".getBytes(StandardCharsets.UTF_8),
                                "chunk-1-v1".getBytes(StandardCharsets.UTF_8));

                Path storeDir = temp.resolve("snapshots");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");
                SnapshotManifest snap1Manifest = new SnapshotStore(storeDir).loadLatestSnapshot();
                assertNotNull(snap1Manifest);
                String snap1 = snap1Manifest.getSnapshotId();

                // 等待跨越秒边界以确保 snap2 获得不同 epoch 与 ID
                long deadline = System.currentTimeMillis() + 3000;
                while (Instant.now().getEpochSecond() <= snap1Manifest.getEpochSeconds()
                                && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                }

                Files.writeString(world.resolve("level.dat"), "level-v2");
                Files.writeString(world.resolve("data/v2-only.txt"), "only-v2");
                writeTwoChunks(world.resolve("region/r.0.0.mca"),
                                "chunk-0-v2".getBytes(StandardCharsets.UTF_8),
                                "chunk-1-v1".getBytes(StandardCharsets.UTF_8));

                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");
                SnapshotManifest snap2Manifest = new SnapshotStore(storeDir).loadLatestSnapshot();
                assertNotNull(snap2Manifest);
                String snap2 = snap2Manifest.getSnapshotId();
                assertNotEquals(snap1, snap2);

                Path exportDir = temp.resolve("exported-world");
                assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(),
                                "snapshot", "export", snap1, "--output", exportDir.toString(), "--zip",
                                "--delete-extra"));
                assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(),
                                "snapshot", "export", snap1, "--output", exportDir.toString(), "--zip", "--dry-run"));

                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "export", snap1, "--output", exportDir.toString(), "--dry-run");
                assertFalse(Files.exists(exportDir));

                // 预先写入多余文件并在非空目标中放入 level.dat，验证 --delete-extra 清理多余文件
                Files.createDirectories(exportDir.resolve("data"));
                Files.writeString(exportDir.resolve("level.dat"), "stale-level");
                Files.writeString(exportDir.resolve("data/stale-extra.txt"), "should-be-deleted");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "export", snap1, "--output", exportDir.toString(), "--delete-extra",
                                "--threads", "1");
                assertEquals("level-v1", Files.readString(exportDir.resolve("level.dat")));
                assertEquals("keep-v1", Files.readString(exportDir.resolve("data/keep.txt")));
                assertFalse(Files.exists(exportDir.resolve("data/stale-extra.txt")));
                assertArrayEquals("chunk-0-v1".getBytes(StandardCharsets.UTF_8),
                                readChunk(exportDir.resolve("region/r.0.0.mca"), 0));

                Path zipTarget = temp.resolve("snap1-bundle");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "export", snap1, "--output", zipTarget.toString(), "--zip", "--threads",
                                "1");
                Path actualZip = temp.resolve("snap1-bundle.zip");
                assertTrue(Files.isRegularFile(actualZip));
                try (var zip = new ZipFile(actualZip.toFile())) {
                        assertNotNull(zip.getEntry("level.dat"));
                        assertNotNull(zip.getEntry("region/r.0.0.mca"));
                }

                Path archiveDir = temp.resolve("archives");
                BackupCli.execute("--snapshot-root", storeDir.toString(), "--archive-root", archiveDir.toString(),
                                "archive", "create", "--snapshot", snap1, "--no-recompress", "--threads", "1");
                BackupCli.execute("--snapshot-root", storeDir.toString(), "--archive-root", archiveDir.toString(),
                                "archive", "create", "--snapshot", snap2, "--base", snap1, "--threads", "1");

                Path fullTar = archiveDir.resolve(snap1 + "[full].tar");
                Path diffTar = archiveDir.resolve(snap1 + "[" + snap2 + "].tar");
                assertTrue(Files.isRegularFile(fullTar));
                assertTrue(Files.isRegularFile(diffTar));

                String verifyOut = captureStdout(() -> BackupCli.execute(
                                "--archive-root", archiveDir.toString(), "archive", "verify"));
                assertTrue(verifyOut.contains("OK"), verifyOut);

                Path diffRestored = temp.resolve("diff-restored");
                BackupCli.execute("--archive-root", archiveDir.toString(),
                                "archive", "export", diffTar.getFileName().toString(),
                                "--output", diffRestored.toString(), "--threads", "1");
                assertEquals("level-v2", Files.readString(diffRestored.resolve("level.dat")));
                assertEquals("only-v2", Files.readString(diffRestored.resolve("data/v2-only.txt")));
                assertArrayEquals("chunk-0-v2".getBytes(StandardCharsets.UTF_8),
                                readChunk(diffRestored.resolve("region/r.0.0.mca"), 0));
                assertArrayEquals("chunk-1-v1".getBytes(StandardCharsets.UTF_8),
                                readChunk(diffRestored.resolve("region/r.0.0.mca"), 1));
        }

        @Test
        void cliSnapshotPinAndUnpinAreIdempotentAndProtectDelete() throws Exception {
                Path world = temp.resolve("pin-world");
                Files.createDirectories(world);
                Files.writeString(world.resolve("level.dat"), "level-pin");

                Path storeDir = temp.resolve("pin-snapshots");
                BackupCli.execute("--snapshot-root", storeDir.toString(),
                                "snapshot", "create", "--world", world.toString(), "--threads", "1");
                String snapId = new SnapshotStore(storeDir).loadLatestSnapshot().getSnapshotId();

                String pinOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "pin", snapId));
                assertTrue(pinOut.contains("Pinned snapshot " + snapId), pinOut);

                String pinAgainOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "pin", snapId));
                assertTrue(pinAgainOut.contains("Snapshot " + snapId + " is already pinned"), pinAgainOut);

                String listOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "list"));
                assertTrue(listOut.contains(snapId + " [PIN]"), listOut);

                IOException deleteErr = assertThrows(IOException.class, () -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "delete", snapId, "--yes"));
                assertTrue(deleteErr.getMessage().contains("unpin"), deleteErr.getMessage());

                String unpinOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "unpin", snapId));
                assertTrue(unpinOut.contains("Unpinned snapshot " + snapId), unpinOut);

                String unpinAgainOut = captureStdout(() -> BackupCli.execute(
                                "--snapshot-root", storeDir.toString(), "snapshot", "unpin", snapId));
                assertTrue(unpinAgainOut.contains("Snapshot " + snapId + " is not pinned"), unpinAgainOut);

                BackupCli.execute("--snapshot-root", storeDir.toString(), "snapshot", "delete", snapId, "--yes");
                assertTrue(new SnapshotStore(storeDir).listSnapshots().isEmpty());
        }

        private static void writeTwoChunks(Path mca, byte[] chunk0, byte[] chunk1) throws IOException {
                Region r = new Region(0, 0, System.currentTimeMillis());
                r.setChunk(0, new ChunkData(chunk0, 0, 0));
                r.setChunk(1, new ChunkData(chunk1, 1, 0));
                AnvilTestSupport.writeRegionAnvil(mca, r, 6);
        }

        private static byte[] readChunk(Path mca, int index) throws IOException {
                ChunkData chunk = AnvilTestSupport.readRegionAnvil(mca).getChunk(index);
                return chunk == null ? null : chunk.getRawChunk();
        }

        @FunctionalInterface
        private interface ThrowingRunnable {
                void run() throws Exception;
        }

        private static String captureStdout(ThrowingRunnable action) throws Exception {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream previous = System.out;
                try {
                        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
                        action.run();
                } finally {
                        System.setOut(previous);
                }
                return buffer.toString(StandardCharsets.UTF_8);
        }
}
