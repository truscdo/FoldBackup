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
 * fall back to a same-directory {@code foldbackup.jar} and search the explicitly
 * supplied mods directory. Data roots are selected by index.db and
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
            String mods = modsDirectory.toAbsolutePath().normalize().toString();
            ScriptWriter.write(backupRoot.resolve("foldbackup-tui.bat"),
                    "/scripts/tui-launcher.bat.tmpl", Map.of("MODS_DIR", mods.replace("%", "%%")));
            ScriptWriter.write(backupRoot.resolve("foldbackup-tui.sh"),
                    "/scripts/tui-launcher.sh.tmpl", Map.of("MODS_DIR", "'" + mods.replace('\\', '/').replace("'", "'\"'\"'") + "'"));
        } catch (IOException e) {
            System.err.println("Failed to install TUI launcher scripts into " + backupRoot + ": " + e.getMessage());
        }
    }
}
