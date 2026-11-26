package io.github.truscdo.foldbackup.core.backup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BackupFilter} 的单元测试——glob 匹配语义。
 */
class BackupFilterTest {

    // ──────────────────────────────────────────────
    // 默认值行为（原硬编码跳过规则）
    // ──────────────────────────────────────────────

    @Test
    void defaults_skipSessionLockAnyDepth() {
        BackupFilter filter = BackupFilter.defaults();
        assertTrue(filter.skipFile("session.lock"), "root session.lock should be skipped");
        assertTrue(filter.skipFile("data/session.lock"), "nested session.lock should be skipped");
        assertTrue(filter.skipDirectory("session.lock"));
    }

    @Test
    void defaults_skipBackupsDirectoryAnyDepth() {
        BackupFilter filter = BackupFilter.defaults();
        assertTrue(filter.skipDirectory("backups"));
        assertTrue(filter.skipDirectory("data/backups"));
        assertTrue(filter.skipFile("backups"));
        assertTrue(filter.skipDirectory("data/.backups"));
    }

    @Test
    void defaults_skipWipFilesAnyDepth() {
        BackupFilter filter = BackupFilter.defaults();
        assertTrue(filter.skipFile("foo.wip"));
        assertTrue(filter.skipFile("data/foo.lrh.wip"), "**/*.wip should cover .lrh.wip");
        assertTrue(filter.skipFile("a/b/c/region.done.wip"));
    }

    @Test
    void defaults_doesNotSkipNormalContent() {
        BackupFilter filter = BackupFilter.defaults();
        assertFalse(filter.skipFile("level.dat"));
        assertFalse(filter.skipFile("region/r.0.0.mca"));
        assertFalse(filter.skipFile("data/foo.dat"));
        assertFalse(filter.skipDirectory("region"));
        assertFalse(filter.skipDirectory("data"));
    }

    @Test
    void defaults_emptyFilesAreNotSkipped() {
        // 过滤器本身不针对 0 字节文件：无条件跳过发生在 RegionProcessor（过滤之前）
        BackupFilter filter = BackupFilter.defaults();
        assertFalse(filter.skipFile("empty.dat"));
    }

    // ──────────────────────────────────────────────
    // 自定义 glob 规则
    // ──────────────────────────────────────────────

    @Test
    void custom_exactRelativePath() {
        BackupFilter filter = new BackupFilter(List.of("data/foo.dat"));
        assertTrue(filter.skipFile("data/foo.dat"));
        assertFalse(filter.skipFile("foo.dat"));
        assertFalse(filter.skipFile("data/bar.dat"));
        assertFalse(filter.skipFile("a/data/foo.dat"));
    }

    @Test
    void custom_singleStarDoesNotCrossSlash() {
        BackupFilter filter = new BackupFilter(List.of("data/*"));
        assertTrue(filter.skipFile("data/foo.dat"));
        assertFalse(filter.skipFile("data/sub/foo.dat"), "* must not cross directory separator");
    }

    @Test
    void custom_doubleStarAnyDepth() {
        BackupFilter filter = new BackupFilter(List.of("**/*.dat"));
        assertTrue(filter.skipFile("foo.dat"));
        assertTrue(filter.skipFile("data/foo.dat"));
        assertTrue(filter.skipFile("a/b/c/foo.dat"));
    }

    @Test
    void custom_questionMarkAndClass() {
        BackupFilter filter = new BackupFilter(List.of("data/file?.txt", "data/[ab].bin"));
        assertTrue(filter.skipFile("data/file1.txt"));
        assertFalse(filter.skipFile("data/file12.txt"));
        assertTrue(filter.skipFile("data/a.bin"));
        assertFalse(filter.skipFile("data/c.bin"));
    }

    @Test
    void custom_braceAlternatives() {
        BackupFilter filter = new BackupFilter(List.of("**/{foo,bar}.dat"));
        assertTrue(filter.skipFile("foo.dat"));
        assertTrue(filter.skipFile("data/bar.dat"));
        assertFalse(filter.skipFile("data/baz.dat"));
    }

    @Test
    void custom_directoryHitSkipsWholeSubtree() {
        BackupFilter filter = new BackupFilter(List.of("**/backups"));
        assertTrue(filter.skipDirectory("data/backups"));
        assertFalse(filter.skipDirectory("data"));
    }

    @Test
    void emptyRulesMatchNothing() {
        BackupFilter filter = new BackupFilter(List.of());
        assertTrue(filter.isEmpty());
        assertFalse(filter.skipFile("session.lock"));
        assertFalse(filter.skipDirectory("backups"));
    }

    @Test
    void nullRulesMatchNothing() {
        BackupFilter filter = new BackupFilter(null);
        assertTrue(filter.isEmpty());
        assertFalse(filter.skipFile("anything"));
    }

    @Test
    void invalidPatternIsIgnoredButOthersStillWork() {
        BackupFilter filter = new BackupFilter(List.of("[unterminated", "**/*.wip"));
        assertTrue(filter.skipFile("foo.wip"), "valid rule must still apply");
        assertFalse(filter.skipDirectory("[unterminated"));
    }

    // ──────────────────────────────────────────────
    // Path 入口（Windows 反斜杠规范化）
    // ──────────────────────────────────────────────

    @Test
    void pathEntry_normalizesSeparators() {
        BackupFilter filter = new BackupFilter(List.of("data/foo.dat"));
        assertTrue(filter.skipFile(Path.of("data", "foo.dat")));

        BackupFilter backups = new BackupFilter(List.of("**/backups"));
        assertTrue(backups.skipDirectory(Path.of("data", "backups")));
        assertTrue(backups.skipDirectory(Path.of("backups")));
    }

    @Test
    void getExcludePatterns_isReadOnlyCopy() {
        List<String> src = new java.util.ArrayList<>(List.of("**/a"));
        BackupFilter filter = new BackupFilter(src);
        src.add("**/b");
        assertEquals(1, filter.getExcludePatterns().size());
        assertFalse(filter.skipFile("b"), "mutating source list must not change filter");
    }

    // ──────────────────────────────────────────────
    // globToRegex 边界
    // ──────────────────────────────────────────────

    @Test
    void globToRegex_escapesRegexMeta() {
        assertEquals("^foo\\.dat$", BackupFilter.globToRegex("foo.dat"));
        assertEquals("^a\\+b$", BackupFilter.globToRegex("a+b"));
    }

    @Test
    void globToRegex_doubleStarSlashMatchesZeroDirs() {
        assertEquals("^(?:.*/)?session\\.lock$", BackupFilter.globToRegex("**/session.lock"));
    }

    // ──────────────────────────────────────────────
    // 严格校验、追加构造与尾 '/' / 前导 '/' 边界
    // ──────────────────────────────────────────────

    @Test
    void defaultsWith_appendsExtraRulesToDefaultFour() {
        BackupFilter same = BackupFilter.defaultsWith(List.of());
        assertEquals(BackupFilter.DEFAULT_PATTERNS, same.getExcludePatterns());
        assertEquals(BackupFilter.DEFAULT_PATTERNS, BackupFilter.defaultsWith(null).getExcludePatterns());

        BackupFilter filter = BackupFilter.defaultsWith(List.of("  **/DistantHorizons.sqlite  ", "data/cache/"));
        assertTrue(filter.skipFile("session.lock"));
        assertTrue(filter.skipDirectory("data/backups"));
        assertTrue(filter.skipFile("foo.wip"));
        assertTrue(filter.skipFile("data/DistantHorizons.sqlite"));
        assertTrue(filter.skipDirectory("data/cache"));
        assertFalse(filter.skipFile("data/cache"), "trailing '/' rule must only skip directories");
        assertFalse(filter.skipFile("level.dat"));
    }

    @Test
    void validatePatternAndOfStrict_rejectBlankLeadingSlashAndInvalidGlobs() {
        for (String blank : new String[] { null, "", "   " }) {
            assertThrows(IllegalArgumentException.class, () -> BackupFilter.validatePattern(blank));
        }
        IllegalArgumentException leading = assertThrows(IllegalArgumentException.class,
                () -> BackupFilter.validatePattern("/region"));
        assertTrue(leading.getMessage().contains("leading '/'"), leading.getMessage());
        assertTrue(leading.getMessage().contains("'region'"), leading.getMessage());

        assertThrows(IllegalArgumentException.class, () -> BackupFilter.validatePattern("/"));
        assertThrows(IllegalArgumentException.class, () -> BackupFilter.validatePattern("//a/b"));

        for (String bad : List.of("[unterminated", "[]", "[!]", "{a,b", "{a,{b,c}", "a}b", "{a,b}}", "foo\\")) {
            assertThrows(IllegalArgumentException.class, () -> BackupFilter.validatePattern(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> BackupFilter.ofStrict(List.of(bad)), bad);
        }
    }

    @Test
    void trailingSlash_matchesDirectoriesOnlyAndNeverMatchesRoot() {
        BackupFilter dirOnly = BackupFilter.ofStrict(List.of("backups/", "data/{cache,tmp}/", "**/sub//"));
        assertTrue(dirOnly.skipDirectory("backups"));
        assertFalse(dirOnly.skipFile("backups"));
        assertTrue(dirOnly.skipDirectory("data/cache"));
        assertTrue(dirOnly.skipDirectory("data/tmp"));
        assertFalse(dirOnly.skipFile("data/cache"));
        assertTrue(dirOnly.skipDirectory("a/sub"));
        assertFalse(dirOnly.skipFile("a/sub"));
        assertFalse(dirOnly.skipDirectory(""));
        assertFalse(dirOnly.skipDirectory(Path.of("")));

        BackupFilter allDirs = BackupFilter.ofStrict(List.of("**/"));
        assertFalse(allDirs.skipDirectory(""));
        assertTrue(allDirs.skipDirectory("region"));
        assertFalse(allDirs.skipFile("level.dat"));

        BackupFilter escapedSlash = BackupFilter.ofStrict(List.of("a\\\\/"));
        assertTrue(escapedSlash.skipDirectory("a\\"));
        assertFalse(escapedSlash.skipFile("a\\"));
    }

    @Test
    void lenientConstructor_supportsTrailingSlashAndIgnoresLeadingSlash() {
        BackupFilter filter = new BackupFilter(List.of("/region", "backups/", "data/ok.dat"));
        assertFalse(filter.skipDirectory("region"));
        assertTrue(filter.skipDirectory("backups"));
        assertFalse(filter.skipFile("backups"));
        assertTrue(filter.skipFile("data/ok.dat"));
    }

    @Test
    void parseExcludeList_splitsTopLevelCommasPreservingBracesBracketsAndEscapes() {
        assertEquals(List.of(), BackupFilter.parseExcludeList(null));
        assertEquals(List.of(), BackupFilter.parseExcludeList("   "));

        assertEquals(
                List.of("**/DistantHorizons.sqlite", "data/{foo,bar}.dat", "data/{a,{b,c}}.txt",
                        "data/[a,b].bin", "foo\\,bar.dat", "cache/"),
                BackupFilter.parseExcludeList(
                        " **/DistantHorizons.sqlite , data/{foo,bar}.dat, data/{a,{b,c}}.txt, data/[a,b].bin, foo\\,bar.dat, cache/ "));

        for (String bad : List.of("a,,b", ",a", "a,", "{a,b", "/region", "a}b")) {
            assertThrows(IllegalArgumentException.class, () -> BackupFilter.parseExcludeList(bad), bad);
        }
    }

    @Test
    void groupSyntax_supportsBracketsAndEscapedBraces() {
        BackupFilter filter = BackupFilter.ofStrict(List.of("{a,[b,c]}", "{\\},z}", "{a,[!]x]}"));
        assertTrue(filter.skipFile("a"));
        assertTrue(filter.skipFile("b"));
        assertTrue(filter.skipFile(","));
        assertTrue(filter.skipFile("}"));
        assertTrue(filter.skipFile("z"));
        assertTrue(filter.skipFile("y"));
        assertFalse(filter.skipFile("]"));
        assertThrows(IllegalArgumentException.class, () -> BackupFilter.validatePattern("{a,[unterminated}"));
    }
}