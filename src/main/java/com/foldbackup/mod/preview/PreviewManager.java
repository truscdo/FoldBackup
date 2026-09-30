package com.foldbackup.mod.preview;

import com.foldbackup.core.rollback.PreviewDifference;
import com.foldbackup.mod.DimensionPath;
import com.foldbackup.mod.FoldBackupMod;
import com.foldbackup.mod.i18n.FoldBackupTranslations;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.*;

/** 会话和世界操作在服务器线程执行，视图过滤与发包在网络线程顺序执行。 */
public final class PreviewManager {
    private final FoldBackupMod mod;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> pending = new HashMap<>();
    private static final DustParticleOptions BORDER = new DustParticleOptions(new Vector3f(0.2f, 0.8f, 1f), 1f);

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final LevelChunk chunk;
        final PreviewPacketFilter view;

        Session(ServerPlayer player, LevelChunk chunk) {
            this.player = player;
            this.level = player.serverLevel();
            this.chunk = chunk;
            this.view = new PreviewPacketFilter(player.connection.getConnection().channel(), chunk.getPos());
        }
    }

    public PreviewManager(FoldBackupMod mod) {
        this.mod = mod;
    }

    private void message(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(FoldBackupTranslations.componentForPlayer(player, mod.getConfig(), key, args));
    }

    private static final class PreviewFailure extends IllegalStateException {
        private final String translationKey;
        private final Object[] args;

        PreviewFailure(String translationKey, Object... args) {
            super(translationKey);
            this.translationKey = translationKey;
            this.args = args == null ? new Object[0] : args.clone();
        }
    }

    public int start(ServerPlayer player, int x, int z, String snapshotId) {
        if (mod.getSnapshotStore() == null) {
            message(player, "foldbackup.command.backend_not_ready");
            return 0;
        }
        Long epochSeconds = null;
        if (snapshotId != null) {
            try {
                epochSeconds = mod.getSnapshotStore().resolveEpoch(snapshotId);
            } catch (IOException e) {
                message(player, "foldbackup.command.info.not_found", snapshotId);
                return 0;
            }
        }
        ChunkPos pos = new ChunkPos(x, z);
        ServerLevel level = player.serverLevel();
        try {
            requireTarget(player, level, pos);
        } catch (PreviewFailure failure) {
            message(player, failure.translationKey, failure.args);
            return 0;
        }
        if (sessions.containsKey(player.getUUID()) || pending.containsKey(player.getUUID()))
            endPreview(player, "foldbackup.preview.switching");
        UUID token = UUID.randomUUID();
        pending.put(player.getUUID(), token);
        message(player, "foldbackup.preview.loading", pos.x, pos.z);
        // 在仓库锁保护下一次性解析指定（或最新）快照。
        mod.readPreviewAsync(pos, epochSeconds, DimensionPath.forLevel(level))
                .whenComplete((raw, error) -> player.server.execute(() -> {
                    if (!token.equals(pending.get(player.getUUID())))
                        return;
                    pending.remove(player.getUUID());
                    if (player.hasDisconnected())
                        return;
                    if (error != null) {
                        FoldBackupMod.LOG.warn("Failed to prepare preview for chunk {}", pos, error);
                        message(player, "foldbackup.preview.prepare_failed");
                        return;
                    }
                    try {
                        LevelChunk chunk = requireTarget(player, level, pos);
                        PreviewData.State target = PreviewData.decode(raw, level, pos);
                        PreviewData.State baseline = PreviewData.capture(level, chunk);
                        Session session = new Session(player, chunk);
                        sessions.put(player.getUUID(), session);
                        player.setCamera(player);
                        sendPreview(session, target, baseline);
                        String language = FoldBackupTranslations.resolveLanguage(player, mod.getConfig());
                        var blockCounts = PreviewData.blockCounts(baseline, target);
                        var blockEntityCounts = PreviewData.blockEntityCounts(baseline, target);
                        message(player, "foldbackup.preview.summary", raw.snapshotId(),
                                countText(language, blockCounts), countText(language, blockEntityCounts));
                        reportEntities(player, raw.entities(), level, chunk);
                        message(player, "foldbackup.preview.limitations.entities_lighting_boundary");
                        message(player, "foldbackup.preview.limitations.block_entities");
                        var unsupported = new TreeMap<String, List<String>>();
                        target.blockEntities().forEach((p, tag) -> unsupported
                                .computeIfAbsent(tag.getString("id"), k -> new ArrayList<>()).add(p.toShortString()));
                        unsupported.forEach((type, positions) -> {
                            // 逐个位置汇报，聊天消息按固定条数拆行。
                            for (int i = 0; i < positions.size(); i += 8)
                                message(player, "foldbackup.preview.unsupported_block_entity", type,
                                        String.join("; ", positions.subList(i, Math.min(i + 8, positions.size()))));
                        });
                        message(player, "foldbackup.preview.limitations.live_updates");
                        message(player, "foldbackup.preview.exit_hint");
                    } catch (Exception e) {
                        FoldBackupMod.LOG.error("Failed to activate preview for chunk {}", pos, e);
                        finish(player, "foldbackup.preview.failed");
                    }
                }));
        return 1;
    }

    private static LevelChunk requireTarget(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        if (player.serverLevel() != level)
            throw new PreviewFailure("foldbackup.preview.wrong_dimension");
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null || player.connection.chunkSender.isPending(pos.toLong())
                || !level.getChunkSource().chunkMap.getPlayers(pos, false).contains(player))
            throw new PreviewFailure("foldbackup.preview.chunk_not_ready", pos.x, pos.z);
        return chunk;
    }

    private void reportEntities(ServerPlayer player, PreviewData.EntityData historical,
            ServerLevel level, LevelChunk chunk) {
        if (historical.error() != null) {
            FoldBackupMod.LOG.warn("Could not read historical entity preview data: {}", historical.error());
            message(player, "foldbackup.preview.entities_not_counted");
            return;
        }
        try {
            String language = FoldBackupTranslations.resolveLanguage(player, mod.getConfig());
            var counts = PreviewDifference.counts(PreviewData.captureEntities(level, chunk), historical.entities());
            message(player, "foldbackup.preview.entity_counts", countText(language, counts));
            if (historical.state() != com.foldbackup.core.rollback.ChunkRollbackSupport.EntitySnapshotState.PRESENT)
                message(player, "foldbackup.preview.entity_snapshot_missing");
        } catch (Exception e) {
            FoldBackupMod.LOG.warn("Could not calculate entity preview difference", e);
            message(player, "foldbackup.preview.entities_not_counted");
        }
    }

    private static String countText(String language, PreviewDifference.Counts counts) {
        return FoldBackupTranslations.textForLanguage(language, "foldbackup.preview.counts",
                counts.added(), counts.removed(), counts.changed());
    }

    private static void sendPreview(Session s, PreviewData.State target, PreviewData.State baseline) {
        // 即使历史方块与原方块状态相同，也要重置客户端方块实体数据。
        Set<BlockPos> reset = new HashSet<>(baseline.blockEntities().keySet());
        reset.addAll(target.blockEntities().keySet());
        for (BlockPos p : reset)
            s.view.send(new ClientboundBlockUpdatePacket(p, Blocks.AIR.defaultBlockState()));
        ShortOpenHashSet all = new ShortOpenHashSet();
        for (short i = 0; i < 4096; i++)
            all.add(i);
        for (int index = 0; index < s.level.getSectionsCount(); index++) {
            var states = new net.minecraft.world.level.chunk.PalettedContainer<>(
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        states.set(x, y, z, target.blocks()[index * 4096 + y * 256 + z * 16 + x]);
            var section = new LevelChunkSection(states, s.chunk.getSection(index).getBiomes());
            s.view.send(new ClientboundSectionBlocksUpdatePacket(
                    SectionPos.of(s.chunk.getPos(), s.level.getSectionYFromSectionIndex(index)), all, section));
        }
    }

    private void endPreview(ServerPlayer player, String reasonKey, Object... args) {
        pending.remove(player.getUUID());
        finish(player, reasonKey, args);
    }

    public int exit(ServerPlayer player) {
        boolean active = pending.containsKey(player.getUUID()) || sessions.containsKey(player.getUUID());
        if (!active) {
            message(player, "foldbackup.preview.not_active");
            return 0;
        }
        endPreview(player, "foldbackup.preview.exited");
        return 1;
    }

    private static void check(Session s) throws Exception {
        if (s.player.getCamera() != s.player || !s.player.isAlive())
            throw new PreviewFailure("foldbackup.preview.ended_player_state_changed");
        if (s.player.serverLevel() != s.level)
            throw new PreviewFailure("foldbackup.preview.ended_dimension_changed");
        var pos = s.chunk.getPos();
        // 已进入预览后，等待完整区块重发不代表客户端卸载；重发包由过滤器拦截。
        if (s.level.getChunkSource().getChunkNow(pos.x, pos.z) != s.chunk)
            throw new PreviewFailure("foldbackup.preview.ended_chunk_reloaded");
        if (!s.level.getChunkSource().chunkMap.getPlayers(pos, false).contains(s.player))
            throw new PreviewFailure("foldbackup.preview.ended_out_of_view");
    }

    private static ClientboundLevelChunkWithLightPacket restoration(Session s) {
        if (s.view.isInvalidated() || s.player.hasDisconnected() || s.player.serverLevel() != s.level)
            return null;
        var chunk = s.level.getChunkSource().getChunkNow(s.chunk.getPos().x, s.chunk.getPos().z);
        if (chunk != null && s.level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).contains(s.player))
            return new ClientboundLevelChunkWithLightPacket(chunk, s.level.getLightEngine(), null, null);
        return null;
    }

    private void finish(ServerPlayer player, String reasonKey, Object... args) {
        Session s = sessions.remove(player.getUUID());
        message(player, reasonKey, args);
        if (s == null)
            return;
        ClientboundLevelChunkWithLightPacket restore = null;
        try {
            restore = restoration(s);
        } catch (Exception e) {
            FoldBackupMod.LOG.error("Preview view restoration failed", e);
        } finally {
            s.view.close(restore);
        }
    }

    public void invalidateChunk(ServerLevel level, ChunkPos pos, String reasonKey, Object... args) {
        for (Session s : List.copyOf(sessions.values()))
            if (s.level == level && s.chunk.getPos().equals(pos))
                finish(s.player, reasonKey, args);
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        for (Session s : List.copyOf(sessions.values())) {
            if (s.view.isInvalidated()) {
                finish(s.player, "foldbackup.preview.ended_client_unloaded");
                continue;
            }
            try {
                check(s);
                if (event.getServer().getTickCount() % 10 == 0)
                    border(s);
            } catch (PreviewFailure failure) {
                finish(s.player, failure.translationKey, failure.args);
            } catch (Exception e) {
                FoldBackupMod.LOG.warn("Preview session ended after an internal error", e);
                finish(s.player, "foldbackup.preview.ended_error");
            }
        }
    }

    private static void border(Session s) {
        double x = s.chunk.getPos().getMinBlockX(), z = s.chunk.getPos().getMinBlockZ();
        double y = Math.max(s.level.getMinBuildHeight(), Math.min(s.level.getMaxBuildHeight() - 1, s.player.getY()));
        for (int i = 0; i <= 16; i++) {
            particle(s, x + i, y, z);
            particle(s, x + i, y, z + 16);
            particle(s, x, y, z + i);
            particle(s, x + 16, y, z + i);
        }
        for (int dy = -4; dy <= 4; dy++)
            for (int dx : new int[] { 0, 16 })
                for (int dz : new int[] { 0, 16 })
                    particle(s, x + dx, y + dy, z + dz);
    }

    private static void particle(Session s, double x, double y, double z) {
        s.level.sendParticles(s.player, BORDER, true, x, y, z, 1, 0, 0, 0, 0);
    }

    @SubscribeEvent
    public void unload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level)
            invalidateChunk(level, event.getChunk().getPos(), "foldbackup.preview.chunk_unloaded");
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && (sessions.containsKey(player.getUUID()) || pending.containsKey(player.getUUID())))
            endPreview(player, "foldbackup.preview.player_left");
    }

    @SubscribeEvent
    public void stop(ServerStoppingEvent event) {
        pending.clear();
        for (Session s : List.copyOf(sessions.values()))
            finish(s.player, "foldbackup.preview.server_stopping");
    }
}
