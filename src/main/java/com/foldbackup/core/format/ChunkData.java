package com.foldbackup.core.format;

import java.util.Objects;

/**
 * A single chunk in a Minecraft region.
 * <p>
 * {@code rawChunk} holds the raw (decompressed) NBT bytes of the chunk data.
 */
public class ChunkData {

    private final byte[] rawChunk;
    private final int x;
    private final int z;

    public ChunkData(byte[] rawChunk, int x, int z) {
        this.rawChunk = Objects.requireNonNull(rawChunk);
        this.x = x;
        this.z = z;
    }

    public byte[] getRawChunk() {
        return rawChunk;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    @Override
    public String toString() {
        return "Chunk[" + x + "," + z + "] " + rawChunk.length + " bytes";
    }
}
