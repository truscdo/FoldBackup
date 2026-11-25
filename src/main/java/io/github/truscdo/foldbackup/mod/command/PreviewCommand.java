package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;

/** {@code /foldbackup preview from <snapshotId> [at <x> <z>]} 与 {@code /foldbackup preview exit} 命令。 */
public final class PreviewCommand {

    @FunctionalInterface
    interface PreviewRunner {
        int start(CommandContext<CommandSourceStack> ctx, @Nullable Integer x, @Nullable Integer z,
                String snapshotId) throws CommandSyntaxException;
    }

    @FunctionalInterface
    interface ExitRunner {
        int exit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private PreviewCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
        register(dispatcher, mod,
                (c, x, z, snapshotId) -> {
                    ServerPlayer player = c.getSource().getPlayerOrException();
                    ChunkPos pos = (x == null || z == null) ? player.chunkPosition() : new ChunkPos(x, z);
                    return mod.getPreviewManager().start(player, pos.x, pos.z, snapshotId);
                },
                c -> mod.getPreviewManager().exit(c.getSource().getPlayerOrException()));
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod,
            PreviewRunner startRunner, ExitRunner exitRunner) {
        dispatcher.register(Commands.literal("foldbackup").requires(CommandPermissions::canManageBackups)
                .then(Commands.literal("preview")
                        .then(Commands.literal("exit")
                                .executes(exitRunner::exit))
                        .then(ChunkCommandArgs.fromSnapshot(mod, false,
                                (c, explicitDimension, x, z, snapshotId) -> startRunner.start(
                                        c, x, z, snapshotId)))));
    }
}
