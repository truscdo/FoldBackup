package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.core.WorldPaths;
import net.minecraft.server.level.ServerLevel;

/** 将服务端维度映射到世界快照中的区域文件路径。 */
public final class DimensionPath {
    private DimensionPath() {}

    public static String forLevel(ServerLevel level) {
        var dimension = level.dimension();
        if (dimension == net.minecraft.world.level.Level.NETHER) return WorldPaths.NETHER_SUBPATH;
        if (dimension == net.minecraft.world.level.Level.END) return WorldPaths.END_SUBPATH;
        if (dimension == net.minecraft.world.level.Level.OVERWORLD) return WorldPaths.OVERWORLD_SUBPATH;
        var id = dimension.location();
        return WorldPaths.customDimensionSubPath(id.getNamespace(), id.getPath());
    }
}
