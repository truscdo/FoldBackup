package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.logging.ConsoleOutputCoordinator;

import java.io.PrintStream;
import java.util.function.LongSupplier;

/** 包级测试桥接：暴露 {@link ProgressPrinter} 的可注入构造器给同核心测试使用。 */
public final class ProgressPrinterAccess {
    private ProgressPrinterAccess() {}

    public static ProgressPrinter create(PrintStream out, LongSupplier clock,
            ConsoleOutputCoordinator coordinator, boolean enableHeartbeat, long heartbeatPeriodMillis) {
        return new ProgressPrinter(out, clock, coordinator, enableHeartbeat, heartbeatPeriodMillis);
    }
}
