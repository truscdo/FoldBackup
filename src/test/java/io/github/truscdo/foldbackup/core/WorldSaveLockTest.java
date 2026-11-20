package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldSaveLockTest {

    @TempDir
    Path temp;

    @Test
    void acquireRoot_missingDirectory_throwsNotFound() {
        Path missing = temp.resolve("missing");
        IOException failure = assertThrows(IOException.class, () -> WorldSaveLock.acquireRoot(missing));
        assertTrue(failure.getMessage().contains("World directory not found"), failure.getMessage());
    }

    @Test
    void acquireRoot_overlappingLockUsesDefaultAndCustomMessages() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        try (WorldSaveLock first = WorldSaveLock.acquireRoot(world)) {
            assertNotNull(first);
            IOException defaultErr = assertThrows(IOException.class, () -> WorldSaveLock.acquireRoot(world));
            assertTrue(defaultErr.getMessage().startsWith("World is locked: "), defaultErr.getMessage());
            assertTrue(defaultErr.getMessage().contains("session.lock"), defaultErr.getMessage());

            IOException customErr = assertThrows(IOException.class,
                    () -> WorldSaveLock.acquireRoot(world, "Custom locked message"));
            assertEquals("Custom locked message", customErr.getMessage());
        }
        try (WorldSaveLock reacquired = WorldSaveLock.acquireRoot(world)) {
            assertNotNull(reacquired);
        }
    }

    @Test
    void acquireRoot_nonLockIoErrorIsNotMislabeledAsLocked() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.createDirectory(world.resolve("session.lock"));

        IOException failure = assertThrows(IOException.class,
                () -> WorldSaveLock.acquireRoot(world, "World is locked; custom"));
        assertFalse(failure.getMessage().contains("World is locked"), failure.getMessage());
    }
}
