package io.github.truscdo.foldbackup.mod.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /foldbackup} 相关命令的权限谓词。
 * <p>
 * 三个命令类共用同一谓词。Brigadier 1.3.10 在合并同名根节点时不会比较
 * {@code requirement}，生效的是首个注册者的谓词，因此各处必须引用同一实现。
 */
public final class CommandPermissions {

    /** 命令要求的最低权限等级。 */
    public static final int PERMISSION_LEVEL = 2;

    private CommandPermissions() {
    }

    /**
     * 命令是否对给定来源可用：权限等级足够，或单机存档主机。
     * <p>
     * 未开启作弊的单人世界中权限等级为 0，此时由主机身份放行；局域网访客不放行。
     */
    public static boolean canManageBackups(CommandSourceStack source) {
        if (source.hasPermission(PERMISSION_LEVEL)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && isSingleplayerHost(source.getServer(), player);
    }

    /**
     * 玩家是否可用命令；登录提示复用同一判定。
     */
    public static boolean canManageBackups(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermissions(PERMISSION_LEVEL)) {
            return true;
        }
        return isSingleplayerHost(player.getServer(), player);
    }

    private static boolean isSingleplayerHost(MinecraftServer server, ServerPlayer player) {
        return server != null
                && !server.isDedicatedServer()
                && server.isSingleplayerOwner(player.getGameProfile());
    }
}
