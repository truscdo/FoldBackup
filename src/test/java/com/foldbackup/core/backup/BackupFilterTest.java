package com.foldbackup.core.backup;

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
}