package io.github.truscdo.foldbackup.core.format;

/** Shared block / chunk / region / grid-slot coordinate conversions. */
public final class ChunkCoordinates {
    private ChunkCoordinates() {}

    public static int blockToChunk(int blockCoord) {
        return blockCoord >> 4;
    }

    public static int entityToChunk(double entityCoord) {
        return (int) Math.floor(entityCoord / 16);
    }

    public static int chunkToRegion(int chunkCoord) {
        return chunkCoord >> 5;
    }

    public static int slotIndex(int chunkX, int chunkZ) {
        return (chunkZ & 31) * 32 + (chunkX & 31);
    }

    public static int chunkX(int regionX, int slot) {
        return regionX * 32 + slot % 32;
    }

    public static int chunkZ(int regionZ, int slot) {
        return regionZ * 32 + slot / 32;
    }

    public static int chunkXExact(int regionX, int slot) {
        return Math.addExact(Math.multiplyExact(regionX, 32), slot % 32);
    }

    public static int chunkZExact(int regionZ, int slot) {
        return Math.addExact(Math.multiplyExact(regionZ, 32), slot / 32);
    }
}
