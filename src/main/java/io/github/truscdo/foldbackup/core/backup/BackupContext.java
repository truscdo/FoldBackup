package io.github.truscdo.foldbackup.core.backup;

import java.nio.file.Path;

/**
 * Contextual metadata for a backup run.
 * <p>
 * Groups related configuration values that were previously passed
 * as individual constructor parameters to {@link BackupCoordinator}.
 */
public record BackupContext(Path worldDir, String worldName,
                            String minecraftVersion) {
}
