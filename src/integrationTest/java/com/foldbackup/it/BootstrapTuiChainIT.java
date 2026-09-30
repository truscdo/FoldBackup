package com.foldbackup.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bootstrap 整链集成测试：真实 fat jar（{@code Main-Class = Bootstrap}，
 * 内嵌 {@code META-INF/jarjar/*.jar}）按启动脚本的两步式路径走通：
 * <ol>
 * <li>{@code extract --output <lib-dir>} — 嵌套库释放到调用方指定目录
 * （幂等：重复执行报 already exported）；</li>
 * <li>{@code tui --snapshot-root <root> --lib <lib-dir>} — bootstrap 重建
 * URLClassLoader → 反射进入 {@code BackupCli.runBootstrapped} →
 * {@code tui} 分发 → {@code BackupTui.launch} → 菜单 → stdin 关闭（EOF）
 * → 打印 {@code Goodbye!} 优雅退出（exit 0，无栈爆炸）。</li>
 * </ol>
 * 同时验证依赖目录独立于仓库，且打开 TUI 不会初始化仓库。
 * <p>
 * 不需要 NeoForge 服务器/RCON——纯进程级测试。
 */
class BootstrapTuiChainIT {

        @TempDir
        Path tmp;

        private Path fatJar() {
                String path = System.getProperty("foldbackup.fatJar");
                assertNotNull(path, "foldbackup.fatJar system property must be set by build.gradle");
                Path jar = Path.of(path);
                assertTrue(Files.isRegularFile(jar), "fat jar must exist: " + jar);
                return jar;
        }

        private record RunResult(int exitCode, String stdout, String stderr) {
        }

        /** Runs the fat jar with empty stdin (EOF immediately). */
        private RunResult runFatJar(Path jar, String... args) throws IOException, InterruptedException {
                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add("java");
                cmd.add("-jar");
                cmd.add(jar.toString());
                cmd.addAll(java.util.Arrays.asList(args));
                var pb = new ProcessBuilder(cmd);
                pb.directory(tmp.toFile());
                // 关键：stdin 重定向到空文件（EOF）——子进程继承的 stdin 永不关闭会让
                // TUI 卡在 readLine，从而挂死本测试。输出先等进程退出再读（管道不阻塞）。
                Path emptyStdin = tmp.resolve("empty-stdin.txt");
                if (!Files.exists(emptyStdin)) {
                        Files.createFile(emptyStdin);
                }
                pb.redirectInput(emptyStdin.toFile());
                pb.redirectErrorStream(false);
                Process p = pb.start();
                boolean finished = p.waitFor(2, TimeUnit.MINUTES);
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(finished, "process timed out");
                return new RunResult(p.exitValue(), stdout, stderr);
        }

        @Test
        void extract_thenTuiWithLib_reachesMenuAndExitsOnEof() throws Exception {
                Path jar = fatJar();
                Path root = tmp.resolve("foldbackups");
                Files.createDirectories(root);
                Path libDir = tmp.resolve("custom libraries");

                // Step 1: extract (two-step script, step 1)
                RunResult extract = runFatJar(jar, "extract", "--output", libDir.toString());
                assertEquals(0, extract.exitCode(),
                                "extract failed\nstdout: " + extract.stdout() + "\nstderr: " + extract.stderr());
                assertTrue(Files.isDirectory(libDir), "explicit library directory must be created");
                try (var libs = Files.list(libDir)) {
                        long jarCount = libs.filter(f -> f.getFileName().toString().endsWith(".jar")).count();
                        assertTrue(jarCount >= 2, "bundled libraries exported, found: " + jarCount);
                }

                // Step 1 again: idempotent
                RunResult extract2 = runFatJar(jar, "extract", "--output", libDir.toString());
                assertEquals(0, extract2.exitCode());
                assertTrue(extract2.stdout().contains("already exported"),
                                "second extract must be a no-op\nstdout: " + extract2.stdout());

                // Step 2: tui --lib with closed stdin → menu → EOF → Goodbye! (exit 0)
                RunResult tui = runFatJar(jar, "tui", "--snapshot-root", root.toString(), "--lib", libDir.toString());
                assertEquals(0, tui.exitCode(),
                                "TUI chain failed\nstdout: " + tui.stdout() + "\nstderr: " + tui.stderr());
                assertTrue(tui.stdout().contains("FoldBackup - Backup Management Tool"),
                                "menu header must appear\nstdout: " + tui.stdout());
                assertTrue(tui.stdout().contains("Goodbye!"),
                                "EOF must exit gracefully\nstdout: " + tui.stdout());
                assertFalse(tui.stderr().contains("NoSuchElementException"),
                                "EOF must not explode with a stack trace\nstderr: " + tui.stderr());
                assertFalse(tui.stderr().contains("Exception"),
                                "no exceptions expected\nstderr: " + tui.stderr());
                try (var entries = Files.list(root)) {
                        assertEquals(0, entries.count(), "opening TUI must not initialize the repository");
                }
                RunResult missingRoot = runFatJar(jar, "tui", "--snapshot-root", tmp.resolve("missing").toString(), "--lib", libDir.toString());
                assertEquals(0, missingRoot.exitCode(), missingRoot.stderr());
                assertFalse(Files.exists(tmp.resolve("missing")), "opening TUI does not create a missing root");
                RunResult noRoot = runFatJar(jar, "tui", "--lib", libDir.toString());
                assertEquals(0, noRoot.exitCode(), noRoot.stderr());
                assertTrue(noRoot.stdout().contains(tmp.toAbsolutePath().normalize().toString()));
                assertEquals(0, runFatJar(jar, "extract").exitCode());
                assertTrue(Files.isDirectory(tmp.resolve(".foldbackup-cli")));
                assertNotEquals(0, runFatJar(jar, root.toString(), "extract").exitCode());
                RunResult archive = runFatJar(jar, "tui", "--archive-root", root.toString(), "--lib", libDir.toString());
                assertEquals(0, archive.exitCode(), archive.stderr());
                assertTrue(archive.stdout().contains("Restore as world files"));
                assertTrue(archive.stdout().contains("Online backup: ENABLED"));
                assertFalse(archive.stdout().contains("Change archive root"));
                RunResult combined = runFatJar(jar, "tui", "--snapshot-root", root.toString(),
                        "--archive-root", root.toString(), "--lib", libDir.toString());
                assertEquals(0, combined.exitCode(), combined.stderr());
                assertTrue(combined.stdout().contains("Archive storage"));
                try (var entries = Files.list(root)) {
                        assertEquals(0, entries.count());
                }
        }

        @Test
        void archiveCommandsRoundTripThroughPackagedCli() throws Exception {
                Path jar = fatJar();
                Path lib = tmp.resolve("runtime");
                RunResult extract = runFatJar(jar, "extract", "--output", lib.toString());
                assertEquals(0, extract.exitCode(), extract.stderr());

                Path world = Files.createDirectories(tmp.resolve("source-world"));
                Files.writeString(world.resolve("level.dat"), "archive round trip");
                Path snapshots = tmp.resolve("snapshots");
                var store = new com.foldbackup.core.store.SnapshotStore(snapshots);
                var blobs = new com.foldbackup.core.store.BlobStore(snapshots, 6);
                var scan = new com.foldbackup.core.backup.RegionProcessor(store::getIndex, blobs, 6)
                        .scanWorld(world, null, null);
                long epoch = 1790673600L;
                String id = com.foldbackup.core.store.SnapshotStore.generateSnapshotId(epoch);
                var snapshot = new com.foldbackup.core.store.SnapshotManifest(id, epoch, "world");
                snapshot.setRegions(scan.getRegions());
                snapshot.setExtraFiles(scan.getFiles());
                store.saveSnapshot(snapshot);

                Path archives = tmp.resolve("archives");
                RunResult create = runFatJar(jar, "archive", "create", "--snapshot", id,
                        "--snapshot-root", snapshots.toString(), "--archive-root", archives.toString(),
                        "--threads", "1", "--lib", lib.toString());
                assertEquals(0, create.exitCode(), create.stderr());
                Path archive = archives.resolve(id + "[full].tar");
                assertTrue(Files.isRegularFile(archive));
                RunResult verify = runFatJar(jar, "archive", "verify", archive.toString(),
                        "--archive-root", archives.toString(), "--lib", lib.toString());
                assertEquals(0, verify.exitCode(), verify.stderr());
                assertTrue(verify.stdout().contains("OK"), verify.stdout());

                Path output = tmp.resolve("exported-world");
                RunResult export = runFatJar(jar, "archive", "export", archive.toString(),
                        "--archive-root", archives.toString(), "--output", output.toString(),
                        "--threads", "1", "--lib", lib.toString());
                assertEquals(0, export.exitCode(), export.stderr());
                assertEquals("archive round trip", Files.readString(output.resolve("level.dat")));

                Path imported = tmp.resolve("imported-snapshots");
                RunResult importResult = runFatJar(jar, "snapshot", "import", "--all-archive",
                        "--archive-root", archives.toString(), "--snapshot-root", imported.toString(),
                        "--threads", "1", "--lib", lib.toString());
                assertEquals(0, importResult.exitCode(), importResult.stderr());
                var restored = new com.foldbackup.core.store.SnapshotStore(imported)
                        .loadSnapshot(snapshot.getEpochSeconds());
                assertNotNull(restored);
                assertEquals(id, restored.getSnapshotId());
                assertEquals(snapshot.getExtraFiles().getFirst().getSourceHash(),
                        restored.getExtraFiles().getFirst().getSourceHash());
        }

        @Test
        void bareTui_withoutExtract_failsWithHint() throws Exception {
                Path jar = fatJar();
                Path root = tmp.resolve("foldbackups-bare");
                Files.createDirectories(root);

                // Nested-jar jar without extract/--lib: bootstrap must fail fast with the hint
                RunResult r = runFatJar(jar, root.toString(), "tui");
                assertNotEquals(0, r.exitCode(), "must fail without --lib");
                assertTrue(r.stderr().contains("extract") || r.stderr().contains("--lib"),
                                "must print the first-run hint\nstderr: " + r.stderr());
        }
}
