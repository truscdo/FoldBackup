package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.rollback.RollbackScope;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.rollback.InMemoryChunkRollbackHandler;
import io.github.truscdo.foldbackup.mod.rollback.RollbackOutcome;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;

/**
 * {@code /foldbackup rollback chunk [blocks|entities] [in <dimension>] <x> <z> [snapshotId]} — 从备份快照回滚单个区块。
 * <p>
 * <b>仅内存操作：</b>此命令仅适用于当前加载在内存中的区块。
 * 如果目标区块未加载，命令将返回错误，提示操作员移动到该区域附近或使用 CLI 工具进行离线恢复。
 * <p>
 * 支持的维度：主世界、下界、末地、自定义数据包维度。
 */
public class ChunkRollbackCommand {

        @FunctionalInterface
        interface RollbackRunner {
                int run(CommandContext<CommandSourceStack> ctx, boolean explicitDimension,
                                int x, int z, @Nullable String snapshotId, RollbackScope scope)
                                throws CommandSyntaxException;
        }

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
                register(dispatcher, mod, (ctx, explicitDimension, x, z, snapshotId, scope) -> rollbackChunk(
                                ctx, mod,
                                explicitDimension ? resolveDimension(ctx) : ctx.getSource().getLevel(),
                                x, z, snapshotId, scope));
        }

        static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod,
                        RollbackRunner runner) {
                // ── 注册：blocks / entities 关键字在坐标之前，与 <x> 整数参数无歧义 ──
                var chunk = attachTarget(Commands.literal("chunk"), mod, RollbackScope.ALL, runner)
                                .then(attachTarget(Commands.literal("blocks"), mod, RollbackScope.BLOCKS, runner))
                                .then(attachTarget(Commands.literal("entities"), mod, RollbackScope.ENTITIES, runner));

                dispatcher.register(
                                Commands.literal("foldbackup")
                                                .requires(CommandPermissions::canManageBackups)
                                                .then(Commands.literal("rollback").then(chunk)));
        }

        private static LiteralArgumentBuilder<CommandSourceStack> attachTarget(
                        LiteralArgumentBuilder<CommandSourceStack> node, FoldBackupMod mod,
                        RollbackScope scope, RollbackRunner runner) {
                // 每个分支通过 ChunkCommandArgs.chunkTarget 获取全新构建器链，避免复用可变 ArgumentBuilder。
                var withDim = Commands.literal("in")
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .then(ChunkCommandArgs.chunkTarget(mod, (ctx, x, z, snapshotId) ->
                                                                runner.run(ctx, true, x, z, snapshotId, scope))));

                var noDim = ChunkCommandArgs.chunkTarget(mod, (ctx, x, z, snapshotId) ->
                                runner.run(ctx, false, x, z, snapshotId, scope));

                return node.then(withDim).then(noDim);
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
                        ServerLevel level, int chunkX, int chunkZ, @Nullable String snapshotId, RollbackScope scope) {
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

                // ── 执行内存回档 ──
                InMemoryChunkRollbackHandler handler = new InMemoryChunkRollbackHandler(
                                backend.store(), backend.blobs());
                // 回档前先关闭目标区块的客户端预览，避免预览与真实状态脱节。
                mod.getPreviewManager().invalidateChunk(level, new ChunkPos(chunkX, chunkZ),
                                "foldbackup.preview.ended_before_rollback");
                RollbackOutcome result = handler.rollbackChunk(level, chunkX, chunkZ, epochSeconds, scope);
                Component message = CommandFeedback.text(source, mod, result.translationKey(), result.args());

                if (result.success()) {
                        source.sendSuccess(() -> message, true);
                        return Command.SINGLE_SUCCESS;
                } else {
                        source.sendFailure(message);
                        return 0;
                }
        }
}
