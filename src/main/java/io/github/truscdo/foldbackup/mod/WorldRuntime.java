package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.StartupScriptInstaller;
import io.github.truscdo.foldbackup.core.backup.BackupContext;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.store.BlobMaintenance;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RepositoryLayout;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.core.task.TaskStatus;
import io.github.truscdo.foldbackup.core.task.TaskType;
import io.github.truscdo.foldbackup.mod.backup.BackupExecutor;
import io.github.truscdo.foldbackup.mod.backup.BackupScheduler;
import io.github.truscdo.foldbackup.mod.backup.WorldAccessor;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.preview.PreviewData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 世界级运行时容器：一个仓库根对应的全部运行状态。
 * <p>
 * 集合了仓库、调度器、执行器与该世界的后台线程池。切换世界时整体替换：
 * 启动事件构造新实例并一次性发布，停止事件关停并丢弃。
 * <p>
 * 仓库初始化失败不会抛出：{@link #initErrorKey()} 非 {@code null} 表示后端不可用，
 * 此时 {@link #snapshotStore()} 与 {@link #blobStore()} 为 {@code null}，备份执行器
 * 返回 {@code NOT_INITIALIZED}，世界仍可正常进入。
 * <p>
 * 不引用 NeoForge 类型（mods 目录由调用方传入），但依赖 Minecraft 服务器对象，
 * 因此不纳入单元测试；路径规则见 {@link RepositoryLayout}。
 */
public final class WorldRuntime {

    /** 仓库初始化失败时向玩家提示的翻译键。 */
    public static final String INIT_ERROR_KEY = "foldbackup.command.backend_init_failed";

    private static final LogUtil LOG = LogUtil.getLogger(WorldRuntime.class);

    private final MinecraftServer server;
    private final Path repoRoot;
    private final Path worldSavePath;
    private final String worldName;
    private final TaskRegistry taskRegistry;
    private final String initErrorKey;

    private volatile SnapshotStore snapshotStore;
    private volatile BlobStore blobStore;
    private volatile BackupScheduler scheduler;
    private volatile BackupExecutor executor;
    private volatile ExecutorService backupExecutor;

    /**
     * 构造并初始化世界运行时。
     *
     * @param server          当前服务器
     * @param baseRoot        备份根
     * @param worldSavePath   世界存档根目录
     * @param dedicatedServer 是否为专用服务器
     * @param config          配置
     * @param modsDirectory   mods 目录（启动脚本的 JAR 搜索路径）
     * @param taskRegistry    共享的任务注册表
     */
    public WorldRuntime(MinecraftServer server, Path baseRoot, Path worldSavePath, boolean dedicatedServer,
            FoldBackupConfig config, Path modsDirectory, TaskRegistry taskRegistry) {
        this.server = server;
        this.worldSavePath = worldSavePath;
        this.repoRoot = RepositoryLayout.repoRoot(baseRoot, worldSavePath, dedicatedServer);
        this.worldName = RepositoryLayout.worldName(worldSavePath);
        this.taskRegistry = taskRegistry;
        this.scheduler = new BackupScheduler(config);
        this.backupExecutor = newWorkerPool();

        String failure = null;
        try {
            // 仓库级副作用：只作用于本仓库，避免清理到其它存档的 .wip
            new BlobMaintenance(repoRoot).cleanupWipFiles();
            StartupScriptInstaller.install(repoRoot, modsDirectory);
            blobStore = new BlobStore(repoRoot, config.getCompressionLevel());
            snapshotStore = new SnapshotStore(repoRoot);
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to initialize backup repository at {}; backups are unavailable for this world",
                    repoRoot, e);
            blobStore = null;
            snapshotStore = null;
            failure = INIT_ERROR_KEY;
        }
        this.initErrorKey = failure;

        BackupCoordinator coordinator = null;
        if (snapshotStore != null) {
            var regionProcessor = new RegionProcessor(snapshotStore::getIndex,
                    blobStore, config.getCompressionLevel(), config.buildBackupFilter(),
                    config.getRegionThreads(), config.getRegionMemoryLimitMB());
            coordinator = new BackupCoordinator(
                    new BackupContext(worldSavePath, worldName, ""),
                    snapshotStore, regionProcessor, config.buildRetentionPolicy());
            // 计时状态属于仓库，后端不可用时无需读取
            scheduler.init(repoRoot);
        }

        var worldAccessor = new WorldAccessor(server);
        this.executor = new BackupExecutor(server, worldAccessor, coordinator, config, scheduler,
                backupExecutor, taskRegistry);

        if (snapshotStore != null) {
            LOG.info("FoldBackup initialized. Backup store: {}", repoRoot);
        }
    }

    private static ExecutorService newWorkerPool() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "foldbackup-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 关停后台线程池并释放全部仓库引用。可重复调用。
     */
    public void shutdown() {
        ExecutorService pool;
        synchronized (this) {
            pool = backupExecutor;
            backupExecutor = null;
            executor = null;
            scheduler = null;
            snapshotStore = null;
            blobStore = null;
        }
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                LOG.warn("Backup executor did not terminate within 10s; tasks forced shutdown");
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 在备份执行器线程上异步删除快照；与仓库读写串行化。
     */
    public CompletableFuture<Void> deleteSnapshotAsync(long epochSeconds) {
        TaskStatus status = taskRegistry.startTask(TaskType.DELETE)
                .setRunning()
                .setMessage("Deleting snapshot at epoch " + epochSeconds + "...");
        SnapshotStore store = snapshotStore;
        ExecutorService pool = backupExecutor;
        if (store == null || pool == null) {
            taskRegistry.fail(status, "Backend not initialized");
            return CompletableFuture.failedFuture(new IllegalStateException("Backend not initialized"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                store.deleteSnapshot(epochSeconds);
                taskRegistry.complete(status);
            } catch (IOException e) {
                taskRegistry.fail(status, "Delete failed: " + e.getMessage());
                throw new CompletionException(e);
            }
        }, pool);
    }

    /**
     * 在备份执行器线程上读取预览数据；与快照清理/删除串行化。
     */
    public CompletableFuture<PreviewData.Raw> readPreviewAsync(ChunkPos pos, Long epochSeconds, String dimension) {
        SnapshotStore store = snapshotStore;
        BlobStore blobs = blobStore;
        ExecutorService pool = backupExecutor;
        if (store == null || blobs == null || pool == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Backend not initialized"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return PreviewData.read(store, blobs, pos, epochSeconds, dimension);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, pool);
    }

    /** 所属服务器；用于停止事件的实例校验。 */
    public MinecraftServer server() {
        return server;
    }

    /** 本世界的仓库根。 */
    public Path repoRoot() {
        return repoRoot;
    }

    /** 世界存档根目录。 */
    public Path worldSavePath() {
        return worldSavePath;
    }

    /** 存档目录名，写入快照元数据。 */
    public String worldName() {
        return worldName;
    }

    /** 后端不可用时的提示键；正常为 {@code null}。 */
    public String initErrorKey() {
        return initErrorKey;
    }

    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public BackupScheduler scheduler() {
        return scheduler;
    }

    public BackupExecutor executor() {
        return executor;
    }
}
