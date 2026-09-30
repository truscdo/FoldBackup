package io.github.truscdo.foldbackup.core.format;

/**
 * Anvil (.mca) constants and filename utilities.
 */
public final class AnvilFormat {

    public static final int ANVIL_SECTOR = 4096;
    public static final int COMPRESSION_TYPE_ZLIB = 2;
    public static final int EXTERNAL_FILE_COMPRESSION_TYPE = 128 + 2; // 0x82

    private AnvilFormat() {
    }

    /**
     * 从文件名（如 "r.0.0.mca" 或 "r.-1.3.mca"）解析区域坐标。
     */
    public static int[] parseRegionCoords(String filename) {
        // 去掉扩展名
        int dot = filename.lastIndexOf('.');
        String stem = (dot >= 0) ? filename.substring(0, dot) : filename;
        String[] parts = stem.split("\\.");
        if (parts.length >= 3 && parts[0].equals("r")) {
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            return new int[] { rx, rz };
        }
        throw new IllegalArgumentException("Filename does not match r.x.z.mca pattern: " + filename);
    }

    /** Returns the external chunk file name for a region slot in grid order. */
    public static String externalChunkFileName(int regionX, int regionZ, int slot) {
        int chunkX = regionX * 32 + slot % 32;
        int chunkZ = regionZ * 32 + slot / 32;
        return "c." + chunkX + "." + chunkZ + ".mcc";
    }
}
