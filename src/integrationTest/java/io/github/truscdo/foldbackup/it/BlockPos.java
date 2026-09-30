package io.github.truscdo.foldbackup.it;

/**
 * 世界空间中的不可变方块坐标。
 *
 * @param x 绝对世界坐标 X
 * @param y 绝对世界坐标 Y
 * @param z 绝对世界坐标 Z
 */
public record BlockPos(int x, int y, int z) {

    /**
     * 将区块坐标 + 偏移量转换为绝对世界坐标。
     *
     * @param chunkX  区块 X
     * @param chunkZ  区块 Z
     * @param offsetX 区块内 X 偏移 (0-15)
     * @param y       绝对 Y
     * @param offsetZ 区块内 Z 偏移 (0-15)
     */
    public static BlockPos fromChunk(int chunkX, int chunkZ, int offsetX, int y, int offsetZ) {
        return new BlockPos(chunkX * 16 + offsetX, y, chunkZ * 16 + offsetZ);
    }
}
