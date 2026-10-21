package io.github.truscdo.foldbackup.mod.config;

import io.github.truscdo.foldbackup.core.backup.BackupFilter;
import io.github.truscdo.foldbackup.core.backup.RetentionPolicy;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupLanguages;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * FoldBackup 的 NeoForge 配置。
 * <p>
 * 使用 NeoForge 的配置系统（TOML）。
 */
public class FoldBackupConfig {

        // --- 通用 ---
        private final ModConfigSpec.BooleanValue enabled;
        private final ModConfigSpec.ConfigValue<String> backupPath;
        private final ModConfigSpec.IntValue compressionLevel;
        private final ModConfigSpec.ConfigValue<List<? extends String>> excludePatterns;

        // --- 调度 ---
        private final ModConfigSpec.IntValue intervalMinutes;
        private final ModConfigSpec.IntValue minIntervalMinutes;
        private final ModConfigSpec.BooleanValue backupOnStart;
        private final ModConfigSpec.BooleanValue backupOnStop;

        // --- Region processing ---
        private final ModConfigSpec.IntValue regionThreads;
        private final ModConfigSpec.IntValue regionMemoryLimitMB;

        // --- 日志 ---
        private final ModConfigSpec.IntValue progressLogIntervalSeconds;

        // --- 保留策略 ---
        private final ModConfigSpec.BooleanValue retentionEnabled;
        private final ModConfigSpec.IntValue maxSizeGB;
        private final ModConfigSpec.IntValue minFreeSpaceGB;
        private final ModConfigSpec.IntValue maxAgeDays;

        // --- Localization ---
        private final ModConfigSpec.ConfigValue<String> defaultLanguage;
        private final ModConfigSpec.BooleanValue autoDetectPlayerLanguage;

        private final ModConfigSpec spec;

        /**
         * 构建配置并注册为 NeoForge 的 {@code SERVER} 配置。
         */
        public FoldBackupConfig(ModContainer container) {
                this();
                container.registerConfig(ModConfig.Type.SERVER, spec);
        }

        /**
         * 仅构建配置规范而不注册（供单元测试对"写文件 → 读文件 → 校验"往返行为作断言）。
         */
        FoldBackupConfig() {
                ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

                // General
                builder.comment("General settings").push("general");
                enabled = builder
                                .comment("Enable automatic backups")
                                .define("enabled", true);
                backupPath = builder
                                .comment("Backup root (relative to game dir, or absolute). "
                                                + "A dedicated server uses it as the repository; singleplayer "
                                                + "creates one repository per world folder under it.")
                                .define("backupPath", "./foldbackups");
                compressionLevel = builder
                                .comment("Zstd compression level (1-22). 3=fast, 6=balanced, 10+=better but slower")
                                .defineInRange("compressionLevel", 6, 1, 22);
                // 必须用 defineListAllowEmpty：普通 define(key, List.of(...)) 的校验器是
                // defaultValue.getClass().isAssignableFrom(读入值的类)，而 List.of 返回
                // java.util.ImmutableCollections$ListN、TOML 数组解析为 java.util.ArrayList，
                // 于是每次加载/热重载都被判为 "Incorrect key ... was corrected"，配置被反复纠正重写。
                excludePatterns = builder
                                .comment("Backup exclusion rules: glob patterns matched against paths relative "
                                                + "to the world directory (using '/' separators, without a leading '/'; "
                                                + "absolute paths are not supported). A matching file is skipped; "
                                                + "a matching directory and its entire subtree are skipped. Supports "
                                                + "*, **, ?, [...], and {...} (e.g. **/session.lock, data/foo.dat). "
                                                + "Empty files are backed up unless matched by a rule. "
                                                + "An empty list disables exclusion entirely; entries that are not "
                                                + "non-blank strings are discarded on load.")
                                .defineListAllowEmpty(
                                                "excludePatterns",
                                                List.of("**/session.lock", "**/backups", "**/.backups", "**/*.wip",
                                                                "**/DistantHorizons.sqlite"),
                                                () -> "**/session.lock",
                                                o -> o instanceof String s && !s.isBlank());
                builder.pop();

                // Scheduling
                builder.comment("Backup scheduling").push("scheduling");
                intervalMinutes = builder
                                .comment("Backup interval (minutes of player online time)")
                                .defineInRange("intervalMinutes", 120, 1, 10080);
                minIntervalMinutes = builder
                                .comment("Minimum wall-clock interval between backups (minutes)")
                                .defineInRange("minIntervalMinutes", 10, 0, 10080);
                backupOnStart = builder
                                .comment("Run a backup when the server starts")
                                .define("backupOnStart", false);
                backupOnStop = builder
                                .comment("Run a backup when the server stops")
                                .define("backupOnStop", false);
                builder.pop();

                // Region processing
                builder.comment("Online backup region processing").push("performance");
                regionThreads = builder
                                .comment("Shared workers for streaming regions and files (0 = auto; about half of visible CPUs)")
                                .defineInRange("regionThreads", 0, 0, 256);
                regionMemoryLimitMB = builder
                                .comment("Streaming task memory budget in MB, including estimated native compression state (0 = auto)")
                                .defineInRange("regionMemoryLimitMB", 0, 0, 1024 * 1024);
                builder.pop();

                // Logging
                builder.comment("Progress logging settings").push("logging");
                progressLogIntervalSeconds = builder
                                .comment("Seconds between progress log messages (1=most verbose, 300=least)")
                                .defineInRange("progressLogIntervalSeconds", 3, 1, 300);
                builder.pop();

                // Retention
                builder.comment("Retention policy for automatic snapshot cleanup").push("retention");
                retentionEnabled = builder
                                .comment("Enable automatic cleanup. May delete all history, including manual backups.")
                                .define("enabled", true);
                maxSizeGB = builder
                                .comment("Repository capacity after cleanup, in GiB (1 GiB = 1024^3 bytes; 0 = unlimited)")
                                .defineInRange("maxSizeGB", 50, 0, 10000);
                minFreeSpaceGB = builder
                                .comment("Minimum filesystem free space target in GiB (1024^3 bytes; 0 = disabled). "
                                                + "This does not reserve space for the next backup.")
                                .defineInRange("minFreeSpaceGB", 10, 0, 10000);
                maxAgeDays = builder
                                .comment("Maximum age in days of 86400 seconds (0 = unlimited). "
                                                + "Capacity limits may delete younger snapshots. All limits zero disables automatic deletion.")
                                .defineInRange("maxAgeDays", 30, 0, 36500);
                builder.pop();

                // Localization
                builder.comment("Localization settings").push("localization");
                defaultLanguage = builder
                                .comment("Default language used when player language detection is disabled or unsupported.")
                                .define("defaultLanguage", "en_us", FoldBackupLanguages::isSupportedLanguage);
                autoDetectPlayerLanguage = builder
                                .comment("Use each player's reported language when it is supported.")
                                .define("autoDetectPlayerLanguage", true);
                builder.pop();

                spec = builder.build();
        }

        // --- 获取器 ---

        public boolean isEnabled() {
                return enabled.get();
        }

        /** 备份根：所有世界的备份根目录。 */
        public Path getBackupRoot() {
                return Path.of(backupPath.get());
        }

        public int getCompressionLevel() {
                return compressionLevel.get();
        }

        /** 当前排除规则（不可变副本；空列表表示不过滤任何内容）。 */
        public List<String> getExcludePatterns() {
                return List.copyOf(excludePatterns.get());
        }

        /**
         * 从当前配置值构建备份目标过滤规则。
         */
        public BackupFilter buildBackupFilter() {
                return new BackupFilter(getExcludePatterns());
        }

        public int getIntervalMinutes() {
                return intervalMinutes.get();
        }

        public int getMinIntervalMinutes() {
                return minIntervalMinutes.get();
        }

        public boolean isBackupOnStart() {
                return backupOnStart.get();
        }

        public boolean isBackupOnStop() {
                return backupOnStop.get();
        }

        public int getRegionThreads() {
                return regionThreads.get();
        }

        public int getRegionMemoryLimitMB() {
                return regionMemoryLimitMB.get();
        }

        public int getProgressLogIntervalSeconds() {
                return progressLogIntervalSeconds.get();
        }

        public boolean isRetentionEnabled() {
                return retentionEnabled.get();
        }

        public int getMaxSizeGB() {
                return maxSizeGB.get();
        }

        public int getMinFreeSpaceGB() {
                return minFreeSpaceGB.get();
        }

        public int getMaxAgeDays() {
                return maxAgeDays.get();
        }

        public String getDefaultLanguage() {
                String language = FoldBackupLanguages.normalizeSupportedLanguage(defaultLanguage.get());
                return language == null ? "en_us" : language;
        }

        public boolean isAutoDetectPlayerLanguage() {
                return autoDetectPlayerLanguage.get();
        }

        public ModConfigSpec getSpec() {
                return spec;
        }

        /**
         * 从当前配置值构建 RetentionPolicy。
         */
        public RetentionPolicy buildRetentionPolicy() {
                return new RetentionPolicy()
                                .setEnabled(retentionEnabled.get())
                                .setMaxSizeBytes(maxSizeGB.get() * 1024L * 1024 * 1024)
                                .setMinFreeSpaceBytes(minFreeSpaceGB.get() * 1024L * 1024 * 1024)
                                .setMaxAgeDays(maxAgeDays.get());
        }
}
