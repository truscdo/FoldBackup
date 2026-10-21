package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.rollback.InMemoryChunkRollbackHandler;
import io.github.truscdo.foldbackup.mod.rollback.RollbackOutcome;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /foldbackup rollback chunk <x> <z> [snapshotId]} — 从备份快照回滚单个区块。
 * <p>
 * <b>仅内存操作：</b>此命令仅适用于当前加载在内存中的区块。
 * 如果目标区块未加载，命令将返回错误，提示操作员移动到该区域附近或使用 CLI 工具进行离线恢复。
 * <p>
 * 支持的维度：主世界、下界、末地、自定义数据包维度。
 */
public class ChunkRollbackCommand {

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
                // ── 分支 A：in <dimension> <x> <z> [snapshotId] ──
                // 每个分支使用自己的构建器链——Brigadier 的 ArgumentBuilder 是可变的，
                // 因此在分支间共享构建器会损坏命令树。
                var withDim = Commands.literal("in")
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z",
                                                                                IntegerArgumentType.integer())
                                                                                // A1：指定快照 ID
                                                                                .then(Commands.argument("snapshotId",
                                                                                                StringArgumentType
                                                                                                                .greedyString())
                                                                                                .suggests(SnapshotIdSuggestions
                                                                                                                .suggestAll(mod::getSnapshotStore))
                                                                                                .executes(ctx -> rollbackChunk(
                                                                                                                ctx,
                                                                                                                mod,
                                                                                                                resolveDimension(
                                                                                                                                ctx),
                                                                                                                IntegerArgumentType
                                                                                                                                .getInteger(ctx, "x"),
                                                                                                                IntegerArgumentType
                                                                                                                                .getInteger(ctx, "z"),
                                                                                                                StringArgumentType
                                                                                                                                .getString(ctx, "snapshotId"))))
                                                                                // A2：使用最新快照
                                                                                .executes(ctx -> rollbackChunk(ctx, mod,
                                                                                                resolveDimension(ctx),
                                                                                                IntegerArgumentType
                                                                                                                .getInteger(ctx, "x"),
                                                                                                IntegerArgumentType
                                                                                                                .getInteger(ctx, "z"),
                                                                                                null)))));

                // ── 分支 B：<x> <z> [snapshotId]（当前维度，向后兼容） ──
                var noDim = Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                // B1：指定快照 ID
                                                .then(Commands.argument("snapshotId",
                                                                StringArgumentType.greedyString())
                                                                .suggests(SnapshotIdSuggestions
                                                                                .suggestAll(mod::getSnapshotStore))
                                                                .executes(ctx -> rollbackChunk(ctx, mod,
                                                                                ctx.getSource().getLevel(),
                                                                                IntegerArgumentType.getInteger(ctx,
                                                                                                "x"),
                                                                                IntegerArgumentType.getInteger(ctx,
                                                                                                "z"),
                                                                                StringArgumentType.getString(ctx,
                                                                                                "snapshotId"))))
                                                // B2：使用最新快照
                                                .executes(ctx -> rollbackChunk(ctx, mod,
                                                                ctx.getSource().getLevel(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                null)));

                // ── 注册 ──
                dispatcher.register(
                                Commands.literal("foldbackup")
                                                .requires(CommandPermissions::canManageBackups)
                                                .then(Commands.literal("rollback")
                                                                .then(Commands.literal("chunk")
                                                                                .then(withDim)
                                                                                .then(noDim))));
        }

        /**
         * 使用 Minecraft 内置的 {@link DimensionArgument#getDimension} 将命令上下文中
         * 的 {@code dimension} 参数解析为已加载的 {@link ServerLevel}。
         *
         * @param ctx 包含 {@code "dimension"} 参数的命令上下文
         * @return 已加载的 {@link ServerLevel}
         * @throws CommandSyntaxException 如果找不到或未加载该维度
         */
        private static ServerLevel resolveDimension(CommandContext<CommandSourceStack> ctx)
                        throws CommandSyntaxException {
                return DimensionArgument.getDimension(ctx, "dimension");
        }

        private static int rollbackChunk(CommandContext<CommandSourceStack> ctx, FoldBackupMod mod,
                        ServerLevel level, int chunkX, int chunkZ, String snapshotId) {
                CommandSourceStack source = ctx.getSource();

                BackendGuard.RollbackHandles backend = BackendGuard.requireRollbackHandles(source, mod);
                if (backend == null) {
                        return 0;
                }

                Long epochSeconds = null;
                if (snapshotId != null) {
                        epochSeconds = BackendGuard.resolveEpoch(source, mod, backend.store(), snapshotId);
                        if (epochSeconds == null) {
                                return 0;
                        }
                }

                // ── 检查目标区块当前是否已加载到内存 ──
                LevelChunk liveChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (liveChunk == null) {
                        CommandFeedback.failure(source, mod, "foldbackup.rollback.chunk_not_loaded_command",
                                        chunkX, chunkZ);
                        return 0;
                }

                // ── 执行内存回档 ──
                InMemoryChunkRollbackHandler handler = new InMemoryChunkRollbackHandler(
                                backend.store(), backend.blobs());
                // 回档前必须先关闭目标区块的客户端预览。
                mod.getPreviewManager().invalidateChunk(level, new ChunkPos(chunkX, chunkZ),
                                "foldbackup.preview.ended_before_rollback");
                RollbackOutcome result = handler.rollbackChunk(level, chunkX, chunkZ, epochSeconds);
                Component message = CommandFeedback.text(source, mod, result.translationKey(), result.args());

                if (result.success()) {
                        source.sendSuccess(() -> message, true);
                } else {
                        source.sendFailure(message);
                }
                return Command.SINGLE_SUCCESS;
        }
}
