package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.LogUtil;

/**
 * 基于 SLF4J 的 Mod 日志后端（由 Minecraft/NeoForge Log4j2 控制输出与级别）。
 */
public final class Slf4jLogBackend implements LogUtil.LogBackend {

    private final org.slf4j.Logger logger;

    public Slf4jLogBackend(String name) {
        this.logger = org.slf4j.LoggerFactory.getLogger(name);
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public void log(LogUtil.Level level, String message, Object... args) {
        switch (level) {
            case DEBUG -> logger.debug(message, args);
            case INFO -> logger.info(message, args);
            case WARN -> logger.warn(message, args);
            case ERROR -> logger.error(message, args);
        }
    }

    @Override
    public void log(LogUtil.Level level, String message, Throwable throwable) {
        switch (level) {
            case DEBUG -> logger.debug(message, throwable);
            case INFO -> logger.info(message, throwable);
            case WARN -> logger.warn(message, throwable);
            case ERROR -> logger.error(message, throwable);
        }
    }
}
