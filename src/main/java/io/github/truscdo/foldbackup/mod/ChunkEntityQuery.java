package io.github.truscdo.foldbackup.mod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

/** 已加载区块实体的只读查询；调用方必须使用服务器线程。 */
public final class ChunkEntityQuery {
    private ChunkEntityQuery() {}

    /** 包含隐藏/不参与刻更新的区段，排除已移除的实体与玩家。 */
    public static List<Entity> nonPlayers(ServerLevel level, ChunkPos chunk) {
        return level.entityManager.sectionStorage.getExistingSectionsInChunk(chunk.toLong())
                .flatMap(section -> section.getEntities())
                .filter(entity -> !entity.isRemoved() && !(entity instanceof Player)).toList();
    }
}
