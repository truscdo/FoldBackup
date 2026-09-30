package com.foldbackup.mod;

import com.foldbackup.core.LogUtil;
import com.foldbackup.core.StartupScriptInstaller;
import com.foldbackup.core.backup.*;
import com.foldbackup.core.store.BlobMaintenance;
import com.foldbackup.core.store.BlobStore;
import com.foldbackup.core.store.SnapshotStore;
import com.foldbackup.core.task.TaskRegistry;
import com.foldbackup.core.task.TaskStatus;
import com.foldbackup.core.task.TaskType;
import com.foldbackup.mod.backup.BackupExecutor;
import com.foldbackup.mod.backup.BackupProgressNotifier;
import com.foldbackup.mod.backup.BackupScheduler;
import com.foldbackup.mod.backup.TickHandler;
import com.foldbackup.mod.backup.WorldAccessor;
import com.foldbackup.mod.command.CommandRegistrationHandler;
import com.foldbackup.mod.config.ConfigEventHandler;
import com.foldbackup.mod.config.FoldBackupConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * FoldBackup 模组的主入口点——将后端、调度器和执行器连接在一起，
 * 并将 Minecraft 事件路由到专用处理类。
 */
@Mod(FoldBackupMod.MOD_ID)
public class FoldBackupMod {

    public static final String MOD_ID = "foldbackup";
    public static final LogUtil LOG = LogUtil.getLogger(MOD_ID);

    private Path backupRoot;
    private Path worldSavePath;
    private MinecraftServer server;
    private FoldBackupConfig config;
    private BackupScheduler scheduler;
    private BackupExecutor executor;
    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final BackupProgressNotifier progressNotifier = new BackupProgressNotifier();
    private SnapshotStore snapshotManager;
    private BlobStore blobStore;
    private ExecutorService backupExecutor;
    private final com.foldbackup.mod.preview.PreviewManager previewManager = new com.foldbackup.mod.preview.PreviewManager(this);

    public FoldBackupMod(IEventBus modBus, ModContainer container) {
        config = new FoldBackupConfig(container);

        // 注册专用处理类——每个类处理一个领域
        modBus.register(new ConfigEventHandler(this::bootstrapStorage));

        var gameBus = NeoForge.EVENT_BUS;
        gameBus.register(new ServerLifecycleHandler(this));
        gameBus.register(new TickHandler(this));
        gameBus.register(new CommandRegistrationHandler(this));
        gameBus.register(previewManager);
    }

    // --- 后端初始化 ---

    void bootstrapStorage() {
        if (backupRoot != null) {
            return;
        }

        backupRoot = config.getBackupPath().isAbsolute()
                ? config.getBackupPath()
                : FMLPaths.GAMEDIR.get().resolve(config.getBackupPath());

        try {
            Files.createDirectories(backupRoot);
        } catch (IOException e) {
            LOG.error("Failed to create backup directory: {}", backupRoot, e);
        }

        cleanupWipFiles(backupRoot);
        StartupScriptInstaller.install(backupRoot, FMLPaths.GAMEDIR.get().resolve("mods"));
    }

    void initializeBackend(Path worldSavePath) {
        bootstrapStorage();
        this.worldSavePath = worldSavePath;

        // 惰性创建单线程执行器——仅在服务器实际启动时创建。
        // 此前这是一个类级静态字段，在类加载时创建，
        // 这在纯客户端环境中导致线程泄漏。
        backupExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "foldbackup-worker");
            t.setDaemon(true);
            return t;
        });

        blobStore = new BlobStore(backupRoot, config.getCompressionLevel());
        try {
            snapshotManager = new SnapshotStore(backupRoot);
        } catch (IOException e) {
            LOG.error("Failed to load index.db; starting with empty index", e);
            throw new RuntimeException("Cannot initialize SnapshotStore", e);
        }

        var regionProcessor = new RegionProcessor(snapshotManager::getIndex,
                blobStore, config.getCompressionLevel(), config.buildBackupFilter(),
                config.getRegionThreads(), config.getRegionMemoryLimitMB());

        var worldAccessor = new WorldAccessor(server);

        // Minecraft 版本在索引中纯粹是文档性元数据；
        // 空字符串由 IndexStore 正确处理（往返时视为 null）。
        var backupContext = new BackupContext(
                worldSavePath,
                worldSavePath.getParent() != null
                        ? worldSavePath.getParent().getFileName().toString()
                        : "world",
                "");
        var coordinator = new BackupCoordinator(
                backupContext, snapshotManager, regionProcessor,
                config.buildRetentionPolicy());

        scheduler = new BackupScheduler(config);
        executor = new BackupExecutor(server, worldAccessor, coordinator, config, scheduler, backupExecutor,
                taskRegistry);

        // 恢复持久化的计时器状态（需要 backupRoot）
        scheduler.init(backupRoot);

        LOG.info("FoldBackup initialized. Backup store: {}", backupRoot);
    }

    void setServer(MinecraftServer server) {
        this.server = server;
    }

    void shutdownBackupExecutor() {
        if (backupExecutor == null)
            return;
        backupExecutor.shutdown();
        try {
            if (!backupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                backupExecutor.shutdownNow();
                LOG.warn("Backup executor did not terminate within 10s; tasks forced shutdown");
            }
        } catch (InterruptedException e) {
            backupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void cleanupWipFiles(Path root) {
        new BlobMaintenance(root).cleanupWipFiles();
    }

    // --- 公共 API（供命令/调度器使用） ---

    public BackupExecutor getExecutor() {
        return executor;
    }

    public BackupProgressNotifier getProgressNotifier() {
        return progressNotifier;
    }

    public TaskRegistry getTaskRegistry() {
        return taskRegistry;
    }

    public BackupScheduler getScheduler() {
        return scheduler;
    }

    public SnapshotStore getSnapshotStore() {
        return snapshotManager;
    }

    public BlobStore getBlobStore() {
        return blobStore;
    }

    public Path getWorldSavePath() {
        return worldSavePath;
    }

    /**
     * 在备份执行器线程上异步删除快照。
     * 进度通过 {@link TaskRegistry} 追踪。
     *
     * @return 删除和 GC 完成时返回的 future
     */
    public CompletableFuture<Void> deleteSnapshotAsync(long epochSeconds) {
        TaskStatus status = taskRegistry.startTask(TaskType.DELETE)
                .setRunning()
                .setMessage("Deleting snapshot at epoch " + epochSeconds + "...");
        return CompletableFuture.runAsync(() -> {
            if (snapshotManager == null) {
                taskRegistry.fail(status, "Backend not initialized");
                throw new CompletionException(
                        new IllegalStateException("Backend not initialized yet"));
            }
            try {
                snapshotManager.deleteSnapshot(epochSeconds);
                taskRegistry.complete(status);
            } catch (IOException e) {
                taskRegistry.fail(status, "Delete failed: " + e.getMessage());
                throw new CompletionException(e);
            }
        }, backupExecutor);
    }

    public FoldBackupConfig getConfig() {
        return config;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public com.foldbackup.mod.preview.PreviewManager getPreviewManager() { return previewManager; }

    public CompletableFuture<com.foldbackup.mod.preview.PreviewData.Raw> readPreviewAsync(
            net.minecraft.world.level.ChunkPos pos, Long epochSeconds, String dimension) {
        // 与本服务器的快照清理/删除操作串行化，避免并发竞争。
        return CompletableFuture.supplyAsync(() -> {
            try { return com.foldbackup.mod.preview.PreviewData.read(snapshotManager, blobStore, pos, epochSeconds, dimension); }
            catch (IOException e) { throw new CompletionException(e); }
        }, backupExecutor);
    }
}
