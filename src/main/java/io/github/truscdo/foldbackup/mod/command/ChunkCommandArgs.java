package io.github.truscdo.foldbackup.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import javax.annotation.Nullable;

/**
 * 构造 {@code <x> <z> [snapshotId]} 命令节点链。
 * Brigadier 的 {@code ArgumentBuilder} 是可变对象，每次调用必须返回全新构建器实例。
 */
final class ChunkCommandArgs {

    @FunctionalInterface
    interface Action {
        int run(CommandContext<CommandSourceStack> ctx, int x, int z, @Nullable String snapshotId)
                throws CommandSyntaxException;
    }

    private ChunkCommandArgs() {}

    static RequiredArgumentBuilder<CommandSourceStack, Integer> chunkTarget(
            FoldBackupMod mod, Action action) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> action.run(ctx,
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "z"),
                                null))
                        .then(Commands.argument("snapshotId", StringArgumentType.greedyString())
                                .suggests(SnapshotIdSuggestions.suggestAll(mod::getSnapshotStore))
                                .executes(ctx -> action.run(ctx,
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"),
                                        StringArgumentType.getString(ctx, "snapshotId")))));
    }
}
