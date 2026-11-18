package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesDimensionSubPathsAndWorldRoots() {
        Path world = tempDir.resolve("world");
        assertEquals("", WorldPaths.dimensionSubPath(world));
        assertEquals("DIM-1/", WorldPaths.dimensionSubPath(world.resolve("DIM-1")));
        assertEquals("DIM1/", WorldPaths.dimensionSubPath(world.resolve("DIM1")));
        assertEquals("dimensions/custom/realm/",
                WorldPaths.dimensionSubPath(world.resolve("dimensions/custom/realm")));

        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world));
        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world.resolve("DIM-1")));
        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world.resolve("DIM1")));
        assertEquals(world.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(world.resolve("dimensions/custom/realm")));
    }
}
