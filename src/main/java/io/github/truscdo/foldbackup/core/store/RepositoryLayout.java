package io.github.truscdo.foldbackup.core.store;

import java.nio.file.Path;

/**
 * 备份根到仓库根的映射规则。
 * <p>
 * 专用服务器只有一个世界，仓库根即备份根；单机（集成服务器）允许同一实例内
 * 存在多个存档，每个存档使用备份根下的独立子目录。索引、blob、计时状态与
 * TUI 启动脚本都位于仓库根下，因此不同存档互不共享。
 * <p>
 * 纯函数，不依赖 Minecraft 类型，便于单元测试。
 */
public final class RepositoryLayout {

    /** 无法从存档路径推出目录名时使用的名称。 */
    public static final String FALLBACK_WORLD_NAME = "world";

    private RepositoryLayout() {
    }

    /**
     * 从世界存档根路径推出存档目录名。
     *
     * @param worldSavePath 世界存档根目录（对应 {@code LevelResource.ROOT}）
     * @return 存档目录名；路径缺失父目录时返回 {@link #FALLBACK_WORLD_NAME}
     */
    public static String worldName(Path worldSavePath) {
        if (worldSavePath == null) {
            return FALLBACK_WORLD_NAME;
        }
        Path parent = worldSavePath.getParent();
        if (parent == null) {
            return FALLBACK_WORLD_NAME;
        }
        Path name = parent.getFileName();
        if (name == null) {
            return FALLBACK_WORLD_NAME;
        }
        String text = name.toString();
        return text.isBlank() ? FALLBACK_WORLD_NAME : text;
    }

    /**
     * 解析仓库根。
     *
     * @param baseRoot        备份根（{@code general.backupPath} 解析结果）
     * @param worldSavePath   世界存档根目录
     * @param dedicatedServer 是否为专用服务器
     * @return 专用服务器返回 {@code baseRoot}，单机返回 {@code baseRoot/<存档目录名>}
     */
    public static Path repoRoot(Path baseRoot, Path worldSavePath, boolean dedicatedServer) {
        if (baseRoot == null) {
            throw new IllegalArgumentException("baseRoot must not be null");
        }
        return dedicatedServer ? baseRoot : baseRoot.resolve(worldName(worldSavePath));
    }
}
