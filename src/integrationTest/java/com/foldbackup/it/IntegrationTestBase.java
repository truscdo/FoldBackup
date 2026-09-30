package com.foldbackup.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 集成测试基类，用于需要运行 NeoForge 服务器的测试。
 *
 * <p>
 * 服务器由 {@link ServerProcessManager} 自动以子进程方式启动，
 * 并在所有测试完成后关闭。
 *
 * <p>
 * 生命周期（PER_CLASS）：
 * <ol>
 * <li>{@code @BeforeAll} 启动服务器子进程，等待 RCON 就绪。</li>
 * <li>每个测试方法通过 RCON 命令执行。</li>
 * <li>{@code @AfterAll} 发送 {@code /stop} 并关闭服务器。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestBase {

    protected static RconClient rcon;
    protected static WorldEditor world;
    protected static Path worldDir;
    protected static Path runDir;
    protected static Path projectDir;
    protected static ServerProcessManager serverManager;
    private static long lastBackupCompleted;
    private boolean runDirectoryPrepared;

    @BeforeAll
    public void setupIntegrationTest() throws Exception {
        projectDir = Path.of("").toAbsolutePath();
        runDir = TestConfig.getServerDir();

        System.out.println("=== IntegrationTestBase Setup ===");
        System.out.println("  project dir: " + projectDir);
        System.out.println("  run dir:     " + runDir);

        // 每个集成测试类使用独立的全新生成目录；测试内部显式重启服务器时
        // 会再次调用本方法，但 runDirectoryPrepared 会保留其世界和快照。
        if (!runDirectoryPrepared) {
            ServerProcessManager.prepareFreshRunDirectory(runDir);
            runDirectoryPrepared = true;
        }

        // 启动服务器子进程并等待 RCON 就绪
        serverManager = new ServerProcessManager(runDir, projectDir);
        serverManager.start();

        // 短暂延迟让 RCON 监听器稳定
        Thread.sleep(1000);

        // 连接 RCON，测试期间保持连接
        rcon = new RconClient(TestConfig.RCON_HOST, TestConfig.RCON_PORT, TestConfig.RCON_PASSWORD);
        rcon.connect();
        System.out.println("  RCON connected.");

        // 初始化辅助工具
        world = new WorldEditor(rcon);

        // 世界目录 — 与 server.properties 中的 level-name 一致
        worldDir = runDir.resolve("world");
        System.out.println("  world dir:  " + worldDir);

        // 验证模组已加载
        verifyModLoaded();
        System.out.println("=== Setup complete ===");
    }

    @AfterAll
    public void teardownIntegrationTest() throws Exception {
        System.out.println("=== IntegrationTestBase Teardown ===");
        if (rcon != null) {
            try {
                rcon.close();
            } catch (IOException e) {
                System.err.println("Error closing RCON: " + e.getMessage());
            }
        }
        try {
            if (serverManager != null)
                serverManager.close();
        } finally {
            archiveServerLogs();
        }
        System.out.println("=== Teardown complete ===");
    }

    /** 下一测试类会清理运行目录，先保存日志和停服线程栈。 */
    private void archiveServerLogs() throws IOException {
        if (runDir == null || !java.nio.file.Files.isDirectory(runDir.resolve("logs")))
            return;
        Path destination = Path.of(System.getProperty("foldbackup.buildDir", "build"))
                .resolve("reports/integrationTest/artifacts").resolve(getClass().getSimpleName());
        java.nio.file.Files.createDirectories(destination);
        try (var files = java.nio.file.Files.list(runDir.resolve("logs"))) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList())
                java.nio.file.Files.copy(file, destination.resolve(file.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 通过检查状态来验证 FoldBackup 模组已加载。
     */
    private void verifyModLoaded() throws IOException {
        String status = rcon.sendCommand("/foldbackup status");
        System.out.println("  Mod status: " + status);
        if (status.contains("Unknown") || status.contains("unknown command")) {
            System.err.println("  WARNING: /foldbackup command not found.");
            System.err.println("  The mod may not be loaded correctly.");
        }
    }

    /**
     * 通过 RCON 执行备份并等待完成。
     *
     * @return 从列表输出中解析的快照 ID
     */
    protected String doBackup() throws IOException, InterruptedException {
        // 快照 ID 精度为秒；连续的快速备份必须落在不同秒，否则会生成相同 ID。
        long delay = 1100 - (System.currentTimeMillis() - lastBackupCompleted);
        if (delay > 0)
            Thread.sleep(delay);
        String previousTask = taskToken(rcon.sendCommand("/foldbackup status"));
        // 1. 触发备份
        String response = rcon.sendCommand("/foldbackup backup --force");
        System.out.println("  Backup trigger: " + response);
        if (!response.contains("Starting backup") && !response.contains("Starting a forced backup"))
            throw new IOException("Backup rejected: " + response);

        // 2. 等待备份开始（状态不再是 Idle）
        long deadline = System.currentTimeMillis() + TestConfig.BACKUP_TIMEOUT_SEC * 1000L;
        boolean started = false;
        boolean completed = false;
        while (System.currentTimeMillis() < deadline) {
            String status = rcon.sendCommand("/foldbackup status");
            if (!taskToken(status).equals(previousTask) && status.contains("Idle")) {
                if (!status.toLowerCase(Locale.ROOT).contains("completed"))
                    throw new IOException("Backup failed: " + status);
                completed = true;
                break;
            }
            if (!status.contains("Idle") && !started) {
                started = true;
                System.out.println("  Backup started: " + status);
            }
            Thread.sleep(500);
        }
        if (!completed)
            throw new IOException("Backup did not complete within timeout");
        lastBackupCompleted = System.currentTimeMillis();
        System.out.println("  Backup completed.");

        // 4. 获取快照 ID
        String list = rcon.sendCommand("/foldbackup list");
        System.out.println("  Snapshots:\n" + list);
        // 从列表输出中解析第一个快照 ID。
        // 快照 ID 格式：S-yyyyMMdd_HHmmss
        // 行中可能包含 Minecraft 颜色代码（§），需要剥离。
        var snapshotPattern = java.util.regex.Pattern.compile("S-\\d{8}_\\d{6}");
        var matcher = snapshotPattern.matcher(list);
        if (matcher.find()) {
            String id = matcher.group();
            System.out.println("  Found snapshot: " + id);
            return id;
        }
        throw new RuntimeException("Could not parse snapshot ID from:\n" + list);
    }

    private static String taskToken(String status) {
        var matcher = java.util.regex.Pattern.compile("bak-\\d+").matcher(status);
        return matcher.find() ? matcher.group() : "";
    }
}
