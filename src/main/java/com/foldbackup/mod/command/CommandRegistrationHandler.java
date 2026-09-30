package com.foldbackup.mod.command;

import com.foldbackup.mod.FoldBackupMod;
import com.mojang.brigadier.CommandDispatcher;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 处理 {@code /foldbackup} 游戏内命令的注册。
 * <p>
 * 委托给 {@link FoldBackupCommand#register(CommandDispatcher, FoldBackupMod)}。
 */
public class CommandRegistrationHandler {

    private final FoldBackupMod mod;

    public CommandRegistrationHandler(FoldBackupMod mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FoldBackupCommand.register(event.getDispatcher(), mod);
        ChunkRollbackCommand.register(event.getDispatcher(), mod);
        PreviewCommand.register(event.getDispatcher(), mod);
    }
}
