package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIoTest {

    @TempDir
    Path tempDir;

    @Test
    void moveReplacingOverwritesExistingDestination() throws Exception {
        Path source = Files.writeString(tempDir.resolve("src.txt"), "new");
        Path dest = Files.writeString(tempDir.resolve("dst.txt"), "old");

        FileIo.moveReplacing(source, dest);

        assertFalse(Files.exists(source));
        assertEquals("new", Files.readString(dest));
    }

    @Test
    void deleteTreeRemovesNestedDirectoriesAndHandlesAbsentRootQuietly() throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("tree/a/b"));
        Files.writeString(nested.resolve("file.txt"), "data");

        FileIo.deleteTree(tempDir.resolve("tree"));
        assertFalse(Files.exists(tempDir.resolve("tree")));

        FileIo.deleteTree(tempDir.resolve("missing"), true, false, FileIo.DeletePolicy.QUIET);
        assertTrue(Files.exists(tempDir));
    }
}
