package com.foldbackup.mod;

import net.minecraft.server.level.ServerLevel;

/** 将服务端维度映射到世界快照中的区域文件路径。 */
public final class DimensionPath {
    private DimensionPath() {}

    public static String forLevel(ServerLevel level) {
        var dimension = level.dimension();
        if (dimension == net.minecraft.world.level.Level.NETHER) return "DIM-1/";
        if (dimension == net.minecraft.world.level.Level.END) return "DIM1/";
        if (dimension == net.minecraft.world.level.Level.OVERWORLD) return "";
        var id = dimension.location();
        return "dimensions/" + id.getNamespace() + "/" + id.getPath() + "/";
    }
}
