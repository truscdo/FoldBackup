package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.core.LogUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 处理 Minecraft 服务器生命周期事件以进行备份管理。
 * <p>
 * 在 {@link ServerStartedEvent} 上：捕获服务器引用并初始化备份后端
 * （数据块存储、快照管理器、调度器、执行器）。
 * 如果配置了启动备份也一并运行。
 * <p>
 * 在 {@link ServerStoppingEvent} 上：持久化调度器状态，如果配置了关闭备份则运行，
 * 然后关闭备份执行器线程池。
 */
public class ServerLifecycleHandler {

    private static final LogUtil LOG = LogUtil.getLogger(ServerLifecycleHandler.class);

    private final FoldBackupMod mod;

    public ServerLifecycleHandler(FoldBackupMod mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        mod.setServer(server);
        mod.initializeBackend(server.getWorldPath(LevelResource.ROOT));
        if (mod.getConfig().isBackupOnStart()) {
            var executor = mod.getExecutor();
            if (executor != null) {
                executor.triggerBackup(false);
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        WorldRuntime runtime = mod.runtime();
        if (runtime == null) {
            mod.getProgressNotifier().clear();
            return;
        }
        // 世界可能已切换：旧世界的停止事件不得关停新世界的运行时。
        if (runtime.server() != event.getServer()) {
            return;
        }
        if (runtime.scheduler() != null) {
            runtime.scheduler().saveState();
        }
        if (mod.getConfig().isBackupOnStop() && runtime.executor() != null) {
            try {
                runtime.executor().triggerBackupBlocking(false);
            } catch (Exception e) {
                LOG.error("Failed to run shutdown backup", e);
            }
        }
        mod.shutdownBackend(runtime);
        mod.getProgressNotifier().clear();
    }
}
