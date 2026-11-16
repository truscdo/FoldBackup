package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.LogUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LogUtilTest {

    @Test
    void setPlatformUpdatesExistingCachedLoggerAndConsoleRespectsThreshold() {
        LogUtil.Platform prevPlatform = LogUtil.getPlatform();
        LogUtil.Level prevLevel = LogUtil.getConsoleLevel();
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
            LogUtil.setPlatform(LogUtil.Platform.CLI);
            LogUtil.setConsoleLevel(LogUtil.Level.WARN);
            LogUtil cached = LogUtil.getLogger("LogUtilTestLogger");
            assertFalse(cached.isDebugEnabled());

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));

            cached.debug("hidden debug");
            cached.info("hidden info");
            cached.warn("cli warn");
            cached.error("cli error");

            assertEquals("", outBytes.toString(StandardCharsets.UTF_8));
            String cliErr = errBytes.toString(StandardCharsets.UTF_8);
            assertTrue(cliErr.contains("[WRN] [LogUtilTestLogger]") && cliErr.contains("cli warn"), cliErr);
            assertTrue(cliErr.contains("[ERR] [LogUtilTestLogger]") && cliErr.contains("cli error"), cliErr);

            // 切换到 TUI 后，已缓存的 logger 实例必须立即切换为 TUI 格式
            errBytes.reset();
            LogUtil.setPlatform(LogUtil.Platform.TUI);
            cached.warn("tui warn");
            String tuiErr = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals("[WRN] tui warn" + System.lineSeparator(), tuiErr);

            // 显式开启 DEBUG 级别
            LogUtil.setConsoleLevel(LogUtil.Level.DEBUG);
            assertTrue(cached.isDebugEnabled());
            cached.debug("visible debug");
            assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("[DBG] visible debug"));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
            LogUtil.setConsoleLevel(prevLevel);
            LogUtil.setPlatform(prevPlatform);
        }
    }
}
