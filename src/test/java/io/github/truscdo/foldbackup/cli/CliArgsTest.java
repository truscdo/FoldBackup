package io.github.truscdo.foldbackup.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliArgsTest {

    @TempDir
    Path tempDir;

    @Test
    void unifiedMissingValueMessagesAcrossEntryPoints() {
        IOException backupMissing = assertThrows(IOException.class,
                () -> BackupCli.runCreate(tempDir, List.of("snapshot", "create", "--world")));
        assertEquals("--world requires a value", backupMissing.getMessage());

        IOException excludeMissing = assertThrows(IOException.class,
                () -> BackupCli.runCreate(tempDir, List.of("snapshot", "create", "--exclude")));
        assertEquals("--exclude requires a value", excludeMissing.getMessage());

        IOException archiveCreateMissing = assertThrows(IOException.class,
                () -> ArchiveCli.runArchiveCreate(tempDir, tempDir,
                        List.of("archive", "create", "--snapshot", "--base")));
        assertEquals("--snapshot requires a value", archiveCreateMissing.getMessage());

        IOException archiveExportMissing = assertThrows(IOException.class,
                () -> ArchiveCli.runArchiveExport(tempDir,
                        List.of("archive", "export", "sample.tar", "--output")));
        assertEquals("--output requires a value", archiveExportMissing.getMessage());

        IllegalArgumentException libMissing = assertThrows(IllegalArgumentException.class,
                () -> BootstrapSupport.parseLibArg(new String[] { "tui", "--lib" }));
        assertEquals("--lib requires a value", libMissing.getMessage());
    }

    @Test
    void unifiedPositiveIntegerMessagesAcrossCommands() {
        for (String bad : List.of("0", "-2", "abc")) {
            IOException createErr = assertThrows(IOException.class,
                    () -> BackupCli.runCreate(tempDir, List.of("snapshot", "create", "--threads", bad)));
            assertEquals("--threads requires a positive integer", createErr.getMessage());

            IOException levelErr = assertThrows(IOException.class,
                    () -> ArchiveCli.runArchiveCreate(tempDir, tempDir,
                            List.of("archive", "create", "--level", bad)));
            assertEquals("--level requires a positive integer", levelErr.getMessage());

            IOException exportErr = assertThrows(IOException.class,
                    () -> ArchiveCli.runArchiveExport(tempDir,
                            List.of("archive", "export", "sample.tar", "--threads", bad)));
            assertEquals("--threads requires a positive integer", exportErr.getMessage());
        }
    }
}
