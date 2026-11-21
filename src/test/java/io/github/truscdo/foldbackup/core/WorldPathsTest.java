package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        assertEquals("dimensions/custom/nested/realm/",
                WorldPaths.dimensionSubPath(world.resolve("dimensions/custom/nested/realm")));

        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world));
        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world.resolve("DIM-1")));
        assertEquals(world.toAbsolutePath().normalize(), WorldPaths.worldRoot(world.resolve("DIM1")));
        assertEquals(world.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(world.resolve("dimensions/custom/realm")));
        assertEquals(world.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(world.resolve("dimensions/custom/nested/realm")));
    }

    @Test
    void handlesWorldNamedDimensionsOrInsideOuterDimensionsDir() throws Exception {
        Path worldNamedDimensions = tempDir.resolve("dimensions");
        assertEquals("", WorldPaths.dimensionSubPath(worldNamedDimensions));
        assertEquals("DIM-1/", WorldPaths.dimensionSubPath(worldNamedDimensions.resolve("DIM-1")));
        assertEquals(worldNamedDimensions.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(worldNamedDimensions));
        assertEquals(worldNamedDimensions.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(worldNamedDimensions.resolve("DIM-1")));

        Path nestedWorld = Files.createDirectories(tempDir.resolve("outer/dimensions/saves/my_world"));
        Files.writeString(nestedWorld.resolve("level.dat"), "marker");
        assertEquals("", WorldPaths.dimensionSubPath(nestedWorld));
        assertEquals("DIM-1/", WorldPaths.dimensionSubPath(nestedWorld.resolve("DIM-1")));
        assertEquals("dimensions/custom/realm/",
                WorldPaths.dimensionSubPath(nestedWorld.resolve("dimensions/custom/realm")));
        assertEquals(nestedWorld.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(nestedWorld));
        assertEquals(nestedWorld.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(nestedWorld.resolve("DIM-1")));
        assertEquals(nestedWorld.toAbsolutePath().normalize(),
                WorldPaths.worldRoot(nestedWorld.resolve("dimensions/custom/realm")));
    }
}
