package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /foldbackup preview <x> <z> [snapshotId]} 与 {@code /foldbackup preview exit} 命令。 */
public final class PreviewCommand {
    private PreviewCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, FoldBackupMod mod) {
        dispatcher.register(Commands.literal("foldbackup").requires(s -> s.hasPermission(2))
                .then(Commands.literal("preview")
                        .then(Commands.literal("exit")
                                .executes(c -> mod.getPreviewManager().exit(
                                        c.getSource().getPlayerOrException())))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> mod.getPreviewManager().start(
                                                c.getSource().getPlayerOrException(),
                                                IntegerArgumentType.getInteger(c, "x"),
                                                IntegerArgumentType.getInteger(c, "z"), null))
                                        .then(Commands.argument("snapshotId", StringArgumentType.greedyString())
                                                .suggests(SnapshotIdSuggestions.suggestAll(mod::getSnapshotStore))
                                                .executes(c -> mod.getPreviewManager().start(
                                                        c.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(c, "x"),
                                                        IntegerArgumentType.getInteger(c, "z"),
                                                        StringArgumentType.getString(c, "snapshotId"))))))));
    }
}
