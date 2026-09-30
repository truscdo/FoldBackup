package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScriptWriter} 单元测试：占位符替换、行尾规范化（.bat → CRLF，
 * .sh → LF）、模板缺失报错。
 */
class ScriptWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void write_replacesPlaceholders_andNormalizesLineEndings() throws IOException {
        Path bat = tempDir.resolve("out.bat");
        Path sh = tempDir.resolve("out.sh");

        ScriptWriter.write(bat, "/scripts/tui-launcher.bat.tmpl",
                Map.of("MODS_DIR", "X:\\mods"));
        ScriptWriter.write(sh, "/scripts/tui-launcher.sh.tmpl",
                Map.of("MODS_DIR", "'/tmp/mods'"));

        String batText = Files.readString(bat, StandardCharsets.UTF_8);
        String shText = Files.readString(sh, StandardCharsets.UTF_8);

        // 占位符已替换，无残留
        assertFalse(batText.contains("{{"), "bat has no leftover placeholders");
        assertFalse(shText.contains("{{"), "sh has no leftover placeholders");
        assertTrue(batText.contains("set \"MODS_DIR=X:\\mods\""));
        assertTrue(shText.contains("MODS_DIR='/tmp/mods'"));

        // 行尾：.bat 强制 CRLF，.sh 强制 LF（hasLoneLf 排除 \r\n\r\n 中的合法 \n）
        assertTrue(batText.contains("\r\n"), "bat uses CRLF");
        assertFalse(hasLoneLf(batText), "bat has no bare LF");
        assertFalse(shText.contains("\r"), "sh uses LF only");
        assertTrue(shText.startsWith("#!/bin/sh\n"));
    }

    @Test
    void write_staticTemplate_withoutReplacements() throws IOException {
        Path sh = tempDir.resolve("foldbackup-tui.sh");
        ScriptWriter.write(sh, "/scripts/tui-launcher.sh.tmpl", Map.of());

        String text = Files.readString(sh, StandardCharsets.UTF_8);
        assertTrue(text.contains("--snapshot-root \"$SCRIPT_DIR\""));
        assertFalse(text.contains("\r"), "sh template has no CR");
    }

    @Test
    void write_missingTemplate_throws() {
        Path out = tempDir.resolve("x.sh");
        assertThrows(IOException.class,
                () -> ScriptWriter.write(out, "/scripts/does-not-exist.tmpl", Map.of()));
        assertFalse(Files.exists(out));
    }

    private static boolean hasLoneLf(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && (i == 0 || text.charAt(i - 1) != '\r')) {
                return true;
            }
        }
        return false;
    }
}
