package io.github.truscdo.foldbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Installs two-step TUI launcher scripts into the backup root (the anchor):
 * <ol>
 * <li>{@code extract} — export the bundled nested jars into
 * {@code <anchor>/.foldbackup-cli/} (a complete library directory is left
 * untouched, so the script can be re-run safely);</li>
 * <li>{@code tui --snapshot-root <anchor> --lib <anchor>/.foldbackup-cli} — launch the TUI through
 * the compatibility aliases retained for existing launcher scripts.</li>
 * </ol>
 * The scripts prefer a same-directory versioned {@code foldbackup-*.jar}, then
 * fall back to a same-directory {@code foldbackup.jar} and search the mods
 * directory, referenced relative to the script so the launcher survives copying
 * or moving the game directory. Data roots are selected by index.db and
 * archive filenames in the script directory. Failure paths: the .bat script pauses
 * on error, the .sh script exits non-zero (a read-only backup root must not
 * fail silently).
 * <p>
 * Script bodies live in {@code resources/scripts/} templates; this class only
 * resolves the anchor path and delegates rendering to {@link ScriptWriter}
 * (placeholder replacement, line-ending normalization, POSIX permissions).
 */
public final class StartupScriptInstaller {

    private StartupScriptInstaller() {
    }

    public static void install(Path backupRoot, Path modsDirectory) {
        if (backupRoot == null) {
            return;
        }

        try {
            Files.createDirectories(backupRoot);
            String mods = relativeMods(backupRoot, modsDirectory);
            ScriptWriter.write(backupRoot.resolve("foldbackup-tui.bat"),
                    "/scripts/tui-launcher.bat.tmpl", Map.of("MODS_DIR", "%~dp0" + mods.replace("%", "%%")));
            ScriptWriter.write(backupRoot.resolve("foldbackup-tui.sh"),
                    "/scripts/tui-launcher.sh.tmpl", Map.of("MODS_DIR",
                            "\"$SCRIPT_DIR\"/'" + mods.replace('\\', '/').replace("'", "'\"'\"'") + "'"));
        } catch (IOException e) {
            System.err.println("Failed to install TUI launcher scripts into " + backupRoot + ": " + e.getMessage());
        }
    }

    /**
     * 用相对路径表达 mods 目录，脚本因此不含任何绝对路径：整包复制游戏目录后启动
     * 脚本仍然可用，也不会把用户名等本机路径写进可被分享的备份目录。
     * <p>
     * 备份根与 mods 目录不在同一盘符时无法用相对路径表达，此时退回 {@code "."}
     * （脚本所在目录），由脚本的“找不到 jar”提示引导把 jar 放到脚本旁。
     */
    private static String relativeMods(Path backupRoot, Path modsDirectory) {
        Path repo = backupRoot.toAbsolutePath().normalize();
        Path mods = modsDirectory.toAbsolutePath().normalize();
        try {
            return repo.relativize(mods).toString();
        } catch (IllegalArgumentException differentRoots) {
            System.err.println("Mods directory " + mods + " is on a different volume than " + repo
                    + "; the TUI launcher will only look beside itself");
            return ".";
        }
    }
}
