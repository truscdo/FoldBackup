package com.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StartupScriptInstaller} 单元测试：脚本释放到备份根、内容关键
 * 断言（mods/ jar 查找、extract + --lib 两步式引导、行尾）。
 */
class StartupScriptInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void install_writesBothScripts() throws IOException {
        Path backupRoot = tempDir.resolve("foldbackups");
        StartupScriptInstaller.install(backupRoot, tempDir.resolve("server mods"));

        String bat = Files.readString(backupRoot.resolve("foldbackup-tui.bat"), StandardCharsets.UTF_8);
        String sh = Files.readString(backupRoot.resolve("foldbackup-tui.sh"), StandardCharsets.UTF_8);

        // mods/ jar 查找（脚本位于备份根，mods/ 在上一级）
        assertTrue(bat.contains("%~dp0foldbackup-*.jar"), "bat finds a versioned jar next to the script");
        assertTrue(sh.contains("\"$SCRIPT_DIR\"/foldbackup-*.jar"), "sh finds a versioned jar next to the script");
        assertTrue(bat.contains("foldbackup-*.jar"), "bat finds jar in mods/");
        assertTrue(sh.contains("foldbackup-*.jar"), "sh finds jar in mods/");
        // 两步式引导：extract 释放嵌套库，再以 --lib 启动 TUI
        assertTrue(bat.contains("extract"), "bat exports bundled libraries first");
        assertTrue(bat.contains("--snapshot-root \"%ROOT%\""));
        assertTrue(bat.contains("--archive-root \"%ROOT%\""));
        assertTrue(sh.contains("--snapshot-root \"$SCRIPT_DIR\""));
        assertTrue(sh.contains("--archive-root \"$SCRIPT_DIR\""));
        assertTrue(sh.contains("server mods"));
        assertTrue(bat.contains("server mods"));
        // extract 失败分支（bat 中括号需转义，避免 cmd 块结束符歧义）
        assertTrue(bat.contains("Error: failed to export bundled libraries"),
                "bat has extract-failure branch");
        assertTrue(sh.contains("Error: failed to export bundled libraries"),
                "sh has extract-failure branch");
        // 无占位符残留
        assertFalse(bat.contains("{{"), "bat has no leftover placeholders");
        assertFalse(sh.contains("{{"), "sh has no leftover placeholders");
        // 行尾规范化
        assertTrue(bat.contains("\r\n"), "bat uses CRLF");
        assertFalse(sh.contains("\r"), "sh uses LF only");
    }

    @Test
    void install_createsMissingRoot() throws IOException {
        Path backupRoot = tempDir.resolve("nested/foldbackups");
        StartupScriptInstaller.install(backupRoot, tempDir.resolve("server mods"));
        assertTrue(Files.isRegularFile(backupRoot.resolve("foldbackup-tui.bat")));
        assertTrue(Files.isRegularFile(backupRoot.resolve("foldbackup-tui.sh")));
    }

    @Test
    void install_nullRoot_isNoOp() {
        assertDoesNotThrow(() -> StartupScriptInstaller.install(null, tempDir));
    }
}
