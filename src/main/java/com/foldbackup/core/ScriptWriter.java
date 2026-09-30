package com.foldbackup.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 从 classpath 资源读取脚本模板，做占位符替换与行尾规范化后写出。
 * <p>
 * 模板统一以 LF 行尾存放在 {@code resources/scripts/}；写出时按目标文件
 * 类型规范化——{@code *.bat} 强制 CRLF（cmd 要求），其余强制 LF，不受
 * git 检出配置（{@code core.autocrlf}）影响。
 * <p>
 * 占位符形如 {@code {{NAME}}}，用简单的字符串替换，不引入模板引擎。
 */
public final class ScriptWriter {

    private ScriptWriter() {
    }

    /**
     * 读取模板、替换占位符并写出为目标脚本。
     *
     * @param target       输出脚本路径（如 {@code foldbackup-tui.bat}）
     * @param resourcePath classpath 资源路径（如 {@code /scripts/tui-launcher.bat.tmpl}）
     * @param replacements 占位符名 → 值
     * @throws IOException 模板缺失或写出失败
     */
    public static void write(Path target, String resourcePath, Map<String, String> replacements) throws IOException {
        String template = readTemplate(resourcePath);
        for (var entry : replacements.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        template = normalizeLineEndings(template, target.getFileName().toString().endsWith(".bat"));

        Files.writeString(target, template, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        setExecutablePermissions(target);
    }

    private static String readTemplate(String resourcePath) throws IOException {
        try (InputStream in = ScriptWriter.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Script template not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeLineEndings(String content, boolean windows) {
        String lf = content.replace("\r\n", "\n");
        return windows ? lf.replace("\n", "\r\n") : lf;
    }

    private static void setExecutablePermissions(Path script) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(script, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows does not support POSIX permissions.
        }
    }
}
