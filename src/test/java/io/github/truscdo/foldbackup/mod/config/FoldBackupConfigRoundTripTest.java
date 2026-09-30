package io.github.truscdo.foldbackup.mod.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.ConfigSpec.CorrectionAction;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置读写往返测试。
 * <p>
 * NeoForge 在加载与热重载时都会对配置文件跑一次 {@code ModConfigSpec.correct}：只要某个键
 * "读回来的值"与规范不一致（含注释不一致），就会记一条
 * "Configuration ... is not correct. Correcting" 警告并把文件重写一遍。默认值类型与
 * TOML 解析结果类型不匹配时（例如用 {@code List.of(...)} 定义列表）就会每次加载都被纠正，
 * 日志被刷屏且文件被反复改写。
 * <p>
 * 因此这里用真实的 TOML 编解码做全键往返：把规范默认值写盘 → 读回 → 断言 0 次纠正。
 */
class FoldBackupConfigRoundTripTest {

    private static final List<String> DEFAULT_PATTERNS = List.of(
            "**/session.lock", "**/backups", "**/.backups", "**/*.wip",
            "**/DistantHorizons.sqlite");

    private static final List<String> EXCLUDE_PATH = List.of("general", "excludePatterns");

    @TempDir
    Path tempDir;

    /** 不注册到 ModContainer 的规范实例，仅用于校验读写行为。 */
    private final FoldBackupConfig config = new FoldBackupConfig();

    private final ModConfigSpec spec = config.getSpec();

    // ──────────────────────────────────────────────
    // 往返校验
    // ──────────────────────────────────────────────

    @Test
    void defaultsRoundTripWithoutCorrections() throws IOException {
        CommentedConfig fresh = TomlFormat.newConfig();
        int filled = spec.correct(fresh, (action, path, oldValue, newValue) -> {
        });
        assertTrue(filled > 0, "空配置应被填入所有默认键");

        RoundTrip trip = roundTrip(fresh, "defaults.toml");

        assertEquals(List.of(), trip.corrections(),
                () -> "默认配置写盘后读回被判定为不一致（会导致每次加载都重写文件）：\n"
                        + String.join("\n", trip.corrections()) + "\n--- 文件内容 ---\n" + trip.text());
    }

    @Test
    void userEditedPatternsAreKept() throws IOException {
        CommentedConfig edited = defaults();
        edited.set(EXCLUDE_PATH, new ArrayList<>(List.of("data/cache", "**/*.tmp")));

        RoundTrip trip = roundTrip(edited, "edited.toml");

        assertEquals(List.of(), trip.corrections(), "用户自定义的排除规则不应被纠正");
        assertEquals(List.of("data/cache", "**/*.tmp"), trip.config().get(EXCLUDE_PATH));
    }

    @Test
    void emptyPatternListDisablesExclusion() throws IOException {
        CommentedConfig edited = defaults();
        edited.set(EXCLUDE_PATH, new ArrayList<String>());

        RoundTrip trip = roundTrip(edited, "empty.toml");

        assertEquals(List.of(), trip.corrections(), "空列表是合法配置（完全不过滤），不应被回填默认规则");
        assertEquals(List.of(), trip.config().get(EXCLUDE_PATH));
    }

    @Test
    void invalidPatternEntriesAreDroppedInsteadOfResettingEverything() throws IOException {
        CommentedConfig edited = defaults();
        edited.set(EXCLUDE_PATH, new ArrayList<>(List.of("  ", 42, "**/backups")));

        RoundTrip trip = roundTrip(edited, "invalid-entry.toml");

        assertEquals(1, trip.corrections().size(), () -> "应只纠正一次：\n" + trip.corrections());
        assertEquals(List.of("**/backups"), trip.config().get(EXCLUDE_PATH),
                "非法元素应被丢弃，合法规则应保留");
    }

    @Test
    void outOfRangeNumberIsClampedToBound() throws IOException {
        CommentedConfig edited = defaults();
        edited.set(List.of("general", "compressionLevel"), 99);

        RoundTrip trip = roundTrip(edited, "clamped.toml");

        assertEquals(1, trip.corrections().size(), () -> "应只纠正一次：\n" + trip.corrections());
        assertEquals(22, ((Number) trip.config().get(List.of("general", "compressionLevel"))).intValue());
    }

    // ──────────────────────────────────────────────
    // 默认值
    // ──────────────────────────────────────────────

    @Test
    void excludePatternsDefaultIncludesDistantHorizonsDatabase() {
        Object value = spec.getValues().get(EXCLUDE_PATH);
        assertInstanceOf(ModConfigSpec.ConfigValue.class, value);
        assertEquals(DEFAULT_PATTERNS, ((ModConfigSpec.ConfigValue<?>) value).getDefault());
    }

    // ──────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────

    private CommentedConfig defaults() {
        CommentedConfig fresh = TomlFormat.newConfig();
        spec.correct(fresh);
        return fresh;
    }

    /**
     * 写出到文件 → 原样读回 → 跑一遍 NeoForge 的校验，收集所有被纠正的键与注释。
     */
    private RoundTrip roundTrip(CommentedConfig toWrite, String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        new TomlWriter().write(toWrite, file.toFile(), WritingMode.REPLACE);

        CommentedConfig loaded = TomlFormat.newConfig();
        new TomlParser().parse(file.toFile(), loaded, ParsingMode.REPLACE, FileNotFoundAction.THROW_ERROR);

        List<String> corrections = new ArrayList<>();
        spec.correct(loaded,
                (CorrectionAction action, List<String> path, Object oldValue,
                        Object newValue) -> corrections.add("值 " + action + " " + String.join(".", path)
                                + "：读回 " + oldValue + " → 纠正为 " + newValue),
                (CorrectionAction action, List<String> path, Object oldValue, Object newValue) -> corrections
                        .add("注释 " + action + " " + String.join(".", path)));

        return new RoundTrip(Files.readString(file, StandardCharsets.UTF_8), loaded, corrections);
    }

    private record RoundTrip(String text, CommentedConfig config, List<String> corrections) {
    }
}
