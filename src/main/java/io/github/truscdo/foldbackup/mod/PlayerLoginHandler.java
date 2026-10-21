package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.mod.command.CommandPermissions;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 玩家登录提示：仓库初始化失败时向可用命令的玩家警告一次。
 * <p>
 * 仓库损坏不阻断进入世界，因此需要在进入后告知运维人员备份不可用。
 * 提示对象与命令可用性使用同一权限谓词，避免向普通玩家暴露运维信息。
 */
public class PlayerLoginHandler {

    private final FoldBackupMod mod;

    public PlayerLoginHandler(FoldBackupMod mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String errorKey = mod.getBackendErrorKey();
        if (errorKey == null) {
            return;
        }
        if (!CommandPermissions.canManageBackups(player)) {
            return;
        }
        player.sendSystemMessage(FoldBackupTranslations.componentForPlayer(
                player, mod.getConfig(), errorKey, mod.getRepoRoot()));
    }
}
