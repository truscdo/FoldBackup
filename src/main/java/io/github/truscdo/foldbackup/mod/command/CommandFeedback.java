package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 命令输出的统一出口：按命令来源的语言取词并发送。
 * <p>
 * 命令类只表达"发什么"，不直接接触翻译 API；取词方式变化时只需改这里。
 */
final class CommandFeedback {

    private CommandFeedback() {
    }

    /** 按来源语言取词，不发送。 */
    static Component text(CommandSourceStack source, FoldBackupMod mod, String key, Object... args) {
        return FoldBackupTranslations.componentForSource(source, mod.getConfig(), key, args);
    }

    /** 发送失败提示。 */
    static void failure(CommandSourceStack source, FoldBackupMod mod, String key, Object... args) {
        source.sendFailure(text(source, mod, key, args));
    }
}
