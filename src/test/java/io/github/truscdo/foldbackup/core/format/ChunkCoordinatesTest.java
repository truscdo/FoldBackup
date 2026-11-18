package io.github.truscdo.foldbackup.core.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkCoordinatesTest {

    @Test
    void convertsNegativeAndPositiveCoordinatesConsistently() {
        assertEquals(-1, ChunkCoordinates.blockToChunk(-1));
        assertEquals(0, ChunkCoordinates.blockToChunk(15));
        assertEquals(-1, ChunkCoordinates.entityToChunk(-0.1));
        assertEquals(1, ChunkCoordinates.entityToChunk(16.0));
        assertEquals(-1, ChunkCoordinates.chunkToRegion(-1));
        assertEquals(1, ChunkCoordinates.chunkToRegion(32));

        int slot = ChunkCoordinates.slotIndex(-1, -2);
        assertEquals(30 * 32 + 31, slot);
        assertEquals(-1, ChunkCoordinates.chunkX(-1, slot));
        assertEquals(-2, ChunkCoordinates.chunkZ(-1, slot));
        assertEquals(-1, ChunkCoordinates.chunkXExact(-1, slot));
        assertEquals(-2, ChunkCoordinates.chunkZExact(-1, slot));
    }

    @Test
    void exactRegionConversionDetectsOverflow() {
        assertThrows(ArithmeticException.class, () -> ChunkCoordinates.chunkXExact(Integer.MAX_VALUE, 0));
        assertThrows(ArithmeticException.class, () -> ChunkCoordinates.chunkZExact(Integer.MIN_VALUE, 0));
    }
}
