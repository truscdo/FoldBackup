package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /foldbackup preview <x> <z> [snapshotId]} 与 {@code /foldbackup preview exit} 命令。 */
public final class PreviewCommand {
    private PreviewCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
        dispatcher.register(Commands.literal("foldbackup").requires(CommandPermissions::canManageBackups)
                .then(Commands.literal("preview")
                        .then(Commands.literal("exit")
                                .executes(c -> mod.getPreviewManager().exit(
                                        c.getSource().getPlayerOrException())))
                        .then(ChunkCommandArgs.chunkTarget(mod, (c, x, z, snapshotId) ->
                                mod.getPreviewManager().start(
                                        c.getSource().getPlayerOrException(), x, z, snapshotId)))));
    }
}
