package com.foldbackup.core.format;

import java.util.Objects;

/**
 * In-memory representation of a Minecraft region (32×32 chunks).
 * <p>
 * Analogous to linear-rs's {@code Region} struct.
 */
public class Region {

    public static final int REGION_DIMENSION = 32;
    public static final int HEADER_CELLS = REGION_DIMENSION * REGION_DIMENSION; // 1024

    private final ChunkData[] chunks;
    private final int regionX;
    private final int regionZ;
    /** Source filesystem modification time in Unix epoch milliseconds. */
    private final long mtimeMillis;
    /** Per-chunk Anvil timestamps in Unix epoch seconds. */
    private final int[] timestamps;

    public Region(int regionX, int regionZ, long mtimeMillis) {
        this.chunks = new ChunkData[HEADER_CELLS];
        this.timestamps = new int[HEADER_CELLS];
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.mtimeMillis = mtimeMillis;
    }

    public Region(ChunkData[] chunks, int[] timestamps, int regionX, int regionZ, long mtimeMillis) {
        this.chunks = Objects.requireNonNull(chunks);
        this.timestamps = Objects.requireNonNull(timestamps);
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.mtimeMillis = mtimeMillis;
    }

    public ChunkData getChunk(int index) {
        return chunks[index];
    }

    public void setChunk(int index, ChunkData chunk) {
        chunks[index] = chunk;
    }

    /** Returns a per-chunk Anvil timestamp in Unix epoch seconds. */
    public int getTimestamp(int index) {
        return timestamps[index];
    }

    /** Sets a per-chunk Anvil timestamp in Unix epoch seconds. */
    public void setTimestamp(int index, int timestamp) {
        timestamps[index] = timestamp;
    }

    public int[] getTimestamps() {
        return timestamps;
    }

    public ChunkData[] getChunks() {
        return chunks;
    }

    public int getRegionX() {
        return regionX;
    }

    public int getRegionZ() {
        return regionZ;
    }

    /** Returns the source filesystem modification time in milliseconds. */
    public long getMtimeMillis() {
        return mtimeMillis;
    }

    public int chunkCount() {
        int count = 0;
        for (ChunkData c : chunks) {
            if (c != null)
                count++;
        }
        return count;
    }

    public int newestTimestamp() {
        int max = 0;
        for (int ts : timestamps) {
            if (ts > max)
                max = ts;
        }
        return max;
    }

    @Override
    public String toString() {
        return "Region[r." + regionX + "." + regionZ + "] chunks=" + chunkCount();
    }
}
