package io.github.truscdo.foldbackup.core;

import java.nio.file.Path;

/**
 * 启动脚本注入的空实现，仅用于 {@code -noscripts} 变体 JAR。
 * <p>
 * 与启用版本的类名、包名和签名一致，因此 {@code WorldRuntime} 的调用点无需
 * 条件编译；该变体不含脚本模板，运行时不会在仓库根写出任何启动脚本。
 */
public final class StartupScriptInstaller {

    private StartupScriptInstaller() {
    }

    public static void install(Path backupRoot, Path modsDirectory) {
        // 该构建产物不含启动脚本模板。
    }
}
