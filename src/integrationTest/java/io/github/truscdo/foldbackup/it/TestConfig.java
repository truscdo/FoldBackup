package io.github.truscdo.foldbackup.it;

import java.nio.file.Path;

/**
 * 集成测试的集中配置。
 *
 * <p>
 * 值来自 Gradle 系统属性或合理的默认值。
 */
public final class TestConfig {

    /** RCON 服务器地址。 */
    public static final String RCON_HOST = "127.0.0.1";

    /** RCON 端口（Minecraft 默认 RCON 端口）。 */
    public static final int RCON_PORT = 25575;

    /** 在生成的 server.properties 中定义的 RCON 密码。 */
    public static final String RCON_PASSWORD = "foldbackup-test-password";

    /** 服务器目录内的世界目录名称。 */
    public static final String WORLD_DIR_NAME = "world";

    /** 相对于世界的区域子目录。 */
    public static final String REGION_DIR_NAME = "region";

    /** 等待服务器启动的最大时间（秒）。 */
    public static final int SERVER_STARTUP_TIMEOUT_SEC = 120;

    /** 等待备份完成的最大时间（秒）。 */
    public static final int BACKUP_TIMEOUT_SEC = 60;

    /** 轮询间隔（毫秒）。 */
    public static final long POLL_INTERVAL_MS = 500;

    private TestConfig() {
    }

    /**
     * 解析本次测试运行使用的服务器目录。
     * 如果属性不存在，回退到 {@code build/integrationTest/run}。
     */
    public static Path getServerDir() {
        // 注意：build.gradle 以 systemProperty 'foldbackup.runDir' 传入此值
        String dir = System.getProperty("foldbackup.runDir");
        if (dir != null) {
            return Path.of(dir);
        }
        String buildDir = System.getProperty("foldbackup.buildDir", "build");
        return Path.of(buildDir, "integrationTest", "run");
    }

    /**
     * Path to the pre-built mod shadow JAR.
     */
    public static Path getModJar() {
        String jar = System.getProperty("foldbackup.modJar");
        if (jar != null) {
            return Path.of(jar);
        }
        return getServerDir().resolve("mods").resolve("foldbackup-0.1.0.jar");
    }
}
