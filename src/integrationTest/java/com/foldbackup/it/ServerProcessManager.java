package com.foldbackup.it;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 管理集成测试中 NeoForge 服务器的生命周期。
 *
 * <p>
 * 服务器自动以子进程方式启动。测试基础设施
 * 使用外层 Gradle 准备的参数直接启动服务器 JVM，等待 RCON 可用，
 * 完成后通过 {@code /stop} 关闭服务器并确认进程退出。
 *
 * <p>
 * 用法：
 * 
 * <pre>
 *   # 单条命令 — 无需单独的终端
 *   gradlew integrationTest --tests "*Overworld*"
 * </pre>
 */
public class ServerProcessManager implements AutoCloseable {

    private static final long SERVER_STOP_TIMEOUT_MS = Duration.ofSeconds(30).toMillis();
    private static final long PROCESS_KILL_TIMEOUT_MS = Duration.ofSeconds(15).toMillis();
    private static final long WORLD_LOCK_TIMEOUT_MS = Duration.ofSeconds(15).toMillis();

    private final Path runDir;
    private final Path projectDir;
    private Process serverProcess;

    /**
     * @param runDir     服务器运行目录（build.gradle 中的 gameDirectory）
     * @param projectDir 项目根目录（gradlew.bat 所在目录）
     */
    public ServerProcessManager(Path runDir, Path projectDir) {
        this.runDir = runDir;
        this.projectDir = projectDir;
    }

    /**
     * 生成服务器配置文件，以子进程方式启动服务器，
     * 并等待 RCON 可用。
     */
    public void start() throws Exception {
        System.out.println("[ServerProcessManager] Starting server at " + runDir);

        // 确保运行目录存在（首次运行或 clean 后 build/integrationTest/run 可能不存在）
        java.nio.file.Files.createDirectories(runDir);

        // 生成新的配置文件
        generateEula(runDir);
        generateServerProperties(runDir);

        // 以子进程方式启动服务器
        startServerProcess();

        // 等待 RCON 可用
        waitForRcon(TestConfig.RCON_HOST, TestConfig.RCON_PORT,
                TestConfig.RCON_PASSWORD, TestConfig.SERVER_STARTUP_TIMEOUT_SEC);
    }

    /** 使用已生成的 devlaunch 参数，持有实际服务器 JVM 的进程。 */
    private void startServerProcess() throws IOException {
        Path moddev = Path.of(System.getProperty("foldbackup.buildDir",
                projectDir.resolve("build").toString())).resolve("moddev").toAbsolutePath();
        for (String name : List.of("serverRunClasspath.txt", "serverRunVmArgs.txt", "serverRunProgramArgs.txt")) {
            if (!Files.isRegularFile(moddev.resolve(name)))
                throw new IOException("Missing server launch artifact: " + moddev.resolve(name)
                        + "; run ./gradlew integrationTest to prepare it");
        }
        String modFolders = System.getProperty("foldbackup.modFolders");
        if (modFolders == null)
            throw new IOException("Missing foldbackup.modFolders from integrationTest task");
        ProcessBuilder pb = new ProcessBuilder(javaTool("java"),
                "@" + moddev.resolve("serverRunClasspath.txt"),
                "@" + moddev.resolve("serverRunVmArgs.txt"),
                "-Dfoldbackup.testMode=true", "-Djava.awt.headless=true",
                "-Dfml.modFolders=" + modFolders,
                "net.neoforged.devlaunch.Main", "@" + moddev.resolve("serverRunProgramArgs.txt"));
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);
        Files.createDirectories(runDir.resolve("logs"));
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(runDir.resolve("logs/server-stdout.log").toFile()));
        serverProcess = pb.start();
        System.out.println("[ServerProcessManager] Server JVM pid=" + serverProcess.pid());
    }

    @Override
    public void close() throws Exception {
        Process process = serverProcess;
        if (process == null)
            return;
        Exception failure = null;
        try {
            boolean portClosed = !isPortOpen(TestConfig.RCON_HOST, TestConfig.RCON_PORT);
            if (!portClosed) {
                System.out.println("[ServerProcessManager] Stopping server...");
                try {
                    stopServerOverRcon();
                } catch (Exception e) {
                    failure = addFailure(failure, e);
                }
                // RCON 关闭仅表示开始退出；仍须确认实际服务器 JVM 退出并释放世界锁。
                portClosed = waitUntil(() -> !isPortOpen(TestConfig.RCON_HOST, TestConfig.RCON_PORT),
                        SERVER_STOP_TIMEOUT_MS);
            }

            boolean processExited = process == null
                    || waitForProcessTreeExit(process.toHandle(), SERVER_STOP_TIMEOUT_MS);
            if (!portClosed || !processExited) {
                failure = addFailure(failure, new IOException("Server shutdown timed out: pid=" + process.pid()));
                if (process != null && isProcessTreeAlive(process.toHandle())) {
                    captureThreadDump(process);
                    System.out.println("[ServerProcessManager] Force killing server process tree...");
                    forceKillProcessTree(process.toHandle());
                }
            }

            portClosed = portClosed || waitUntil(() -> !isPortOpen(TestConfig.RCON_HOST, TestConfig.RCON_PORT),
                    PROCESS_KILL_TIMEOUT_MS);
            if (!portClosed)
                failure = addFailure(failure, new IOException(
                        "RCON port did not close after server shutdown"));
            if (!waitForWorldUnlocked(runDir, WORLD_LOCK_TIMEOUT_MS)) {
                failure = addFailure(failure, new IOException(
                        "World session.lock is still held after server shutdown"));
            }
            if (process != null && !waitForProcessTreeExit(process.toHandle(), PROCESS_KILL_TIMEOUT_MS)) {
                failure = addFailure(failure,
                        new IOException("Server process tree did not exit: pid=" + process.pid()));
            }
            if (failure == null)
                System.out.println("[ServerProcessManager] Server stopped.");
        } catch (Exception e) {
            failure = addFailure(failure, e);
        } finally {
            if (process != null && isProcessTreeAlive(process.toHandle())) {
                try {
                    System.out.println("[ServerProcessManager] Final process-tree cleanup...");
                    forceKillProcessTree(process.toHandle());
                    if (!waitForProcessTreeExit(process.toHandle(), PROCESS_KILL_TIMEOUT_MS)) {
                        failure = addFailure(failure, new IOException(
                                "Final server process-tree cleanup did not finish: pid=" + process.pid()));
                    }
                } catch (Exception e) {
                    failure = addFailure(failure, e);
                }
            }
            serverProcess = null;
        }
        if (failure != null)
            throw failure;
    }

    private static String javaTool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name + (isWindows() ? ".exe" : "")).toString();
    }

    private void captureThreadDump(Process server) {
        Path output = runDir.resolve("logs/stop-jstack-" + server.pid() + ".txt");
        Process diagnostic = null;
        try {
            Files.createDirectories(output.getParent());
            diagnostic = new ProcessBuilder(javaTool("jcmd"), Long.toString(server.pid()), "Thread.print")
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!diagnostic.waitFor(8, TimeUnit.SECONDS))
                System.err.println("Thread dump timed out: " + output);
            else if (diagnostic.exitValue() != 0)
                System.err.println("Thread dump failed; see " + output);
        } catch (IOException e) {
            System.err.println("Cannot capture server thread dump: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (diagnostic != null && diagnostic.isAlive()) {
                diagnostic.destroyForcibly();
                try {
                    diagnostic.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 删除生成的世界/仓库目录并重新创建。本方法不拥有可能正在使用固定
     * 集成测试端口或世界的进程，因此绝不会在此停止或强杀任何活动服务器。
     *
     * <p>
     * 该方法刻意只在每个集成测试类的首个服务器启动前调用，
     * 而不是在测试内部的主动重启期间调用。
     */
    public static void prepareFreshRunDirectory(Path runDir) throws Exception {
        if (isPortOpen(TestConfig.RCON_HOST, TestConfig.RCON_PORT)) {
            throw new IOException("RCON port " + TestConfig.RCON_PORT
                    + " is occupied; refusing to clean the integration-test world");
        }

        if (!waitForWorldUnlocked(runDir, WORLD_LOCK_TIMEOUT_MS)) {
            throw new IOException("World session.lock is still held before integration-test cleanup");
        }

        deleteRecursively(runDir);
        Files.createDirectories(runDir);
    }

    private static void stopServerOverRcon() throws IOException {
        try (RconClient rcon = new RconClient(
                TestConfig.RCON_HOST, TestConfig.RCON_PORT, TestConfig.RCON_PASSWORD)) {
            rcon.connect();
            rcon.sendCommand("/stop");
            System.out.println("[ServerProcessManager] /stop sent, waiting for shutdown...");
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory))
            return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return true;
            Thread.sleep(250);
        }
        return condition.getAsBoolean();
    }

    private static boolean isPortOpen(String host, int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitForWorldUnlocked(Path runDir, long timeoutMs) throws InterruptedException {
        Path lockFile = runDir.resolve(TestConfig.WORLD_DIR_NAME).resolve("session.lock");
        if (!Files.exists(lockFile))
            return true;
        return waitUntil(() -> canLock(lockFile), timeoutMs);
    }

    private static boolean canLock(Path lockFile) {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null)
                return false;
            lock.release();
            return true;
        } catch (IOException | OverlappingFileLockException e) {
            return false;
        }
    }

    private static boolean isProcessTreeAlive(ProcessHandle root) {
        return root.isAlive() || root.descendants().anyMatch(ProcessHandle::isAlive);
    }

    private static boolean waitForProcessTreeExit(ProcessHandle root, long timeoutMs) throws InterruptedException {
        return waitUntil(() -> !isProcessTreeAlive(root), timeoutMs);
    }

    /** 仅终止由本管理器启动的进程树。 */
    private static void forceKillProcessTree(ProcessHandle root) throws IOException, InterruptedException {
        if (isWindows() && root.isAlive()) {
            Process killer = new ProcessBuilder("taskkill", "/PID", Long.toString(root.pid()), "/T", "/F")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            killer.waitFor(10, TimeUnit.SECONDS);
        }

        List<ProcessHandle> handles = new ArrayList<>(root.descendants().toList());
        for (int i = handles.size() - 1; i >= 0; i--) {
            ProcessHandle handle = handles.get(i);
            if (handle.isAlive())
                handle.destroyForcibly();
        }
        if (root.isAlive())
            root.destroyForcibly();
    }

    private static Exception addFailure(Exception current, Exception next) {
        if (current == null)
            return next;
        current.addSuppressed(next);
        return current;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    // ── 静态辅助方法 ──

    public static void waitForRcon(String host, int port, String password, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        System.out.print("[ServerProcessManager] Waiting for RCON at " + host + ":" + port);

        // 阶段 1：等待 TCP 端口打开（无需认证）— 更快，无资源泄漏
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket s = new java.net.Socket(host, port);
                s.setSoTimeout(2000);
                s.close();
                // 端口已打开 — RCON 监听器正在运行
                System.out.println(" (port open)");
                break;
            } catch (Exception e) {
                // 尚未就绪
            }
            Thread.sleep(1000);
            System.out.print(".");
        }

        // 阶段 2：等待成功的 RCON 认证 + list 命令
        while (System.currentTimeMillis() < deadline) {
            try (RconClient rcon = new RconClient(host, port, password)) {
                rcon.connect();
                System.out.println(" READY");
                return;
            } catch (Exception e) {
                // RCON 尚未就绪
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Server not ready after " + timeoutSeconds + "s on "
                + host + ":" + port + ". Start the server manually: gradlew runServer");
    }

    public static void generateEula(Path dir) throws IOException {
        java.nio.file.Files.createDirectories(dir);
        var f = dir.resolve("eula.txt");
        java.nio.file.Files.writeString(f, "eula=true\n", java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void generateServerProperties(Path dir) throws IOException {
        java.nio.file.Files.createDirectories(dir);
        var f = dir.resolve("server.properties");
        var props = new java.util.Properties();
        props.setProperty("enable-rcon", "true");
        props.setProperty("rcon.port", String.valueOf(TestConfig.RCON_PORT));
        props.setProperty("rcon.password", TestConfig.RCON_PASSWORD);
        props.setProperty("online-mode", "false");
        props.setProperty("difficulty", "peaceful");
        props.setProperty("spawn-protection", "0");
        props.setProperty("gamemode", "creative");
        props.setProperty("level-seed", "foldbackup-test");
        props.setProperty("level-name", TestConfig.WORLD_DIR_NAME);
        props.setProperty("max-players", "1");
        props.setProperty("allow-cheats", "true");
        props.setProperty("pvp", "false");
        props.setProperty("enable-command-block", "true");
        try (var out = java.nio.file.Files.newOutputStream(f)) {
            props.store(out, "FoldBackup Integration Test - auto-generated");
        }
    }
}
