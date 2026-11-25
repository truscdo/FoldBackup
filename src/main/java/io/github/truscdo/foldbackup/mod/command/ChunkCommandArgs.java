package io.github.truscdo.foldbackup.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;

import javax.annotation.Nullable;

/**
 * 构造 {@code from <snapshotId> [at <x> <z> | in <dimension> at <x> <z>]} 命令节点链。
 * Brigadier 的 {@code ArgumentBuilder} 是可变对象，每次调用必须返回全新构建器实例。
 */
final class ChunkCommandArgs {

    @FunctionalInterface
    interface Action {
        int run(CommandContext<CommandSourceStack> ctx, boolean explicitDimension,
                @Nullable Integer chunkX, @Nullable Integer chunkZ, String snapshotId)
                throws CommandSyntaxException;
    }

    private ChunkCommandArgs() {}

    static LiteralArgumentBuilder<CommandSourceStack> fromSnapshot(
            FoldBackupMod mod, boolean allowDimension, Action action) {
        var snapshotArg = Commands.argument("snapshotId", StringArgumentType.word())
                .suggests(SnapshotIdSuggestions.suggestAll(mod::getSnapshotStore))
                .executes(ctx -> action.run(ctx, false, null, null,
                        StringArgumentType.getString(ctx, "snapshotId")))
                .then(atCoords(false, action));

        if (allowDimension) {
            snapshotArg.then(Commands.literal("in")
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                            .then(atCoords(true, action))));
        }

        return Commands.literal("from").then(snapshotArg);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> atCoords(
            boolean explicitDimension, Action action) {
        return Commands.literal("at")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> action.run(ctx, explicitDimension,
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"),
                                        StringArgumentType.getString(ctx, "snapshotId")))));
    }
}
