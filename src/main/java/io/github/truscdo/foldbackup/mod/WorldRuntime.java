package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.backup.BackupContext;
import io.github.truscdo.foldbackup.core.backup.BackupCoordinator;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.store.BlobMaintenance;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.RepositoryBusyException;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 世界级运行时容器：一个仓库根对应的全部运行状态。
 * <p>
 * 集合了仓库、调度器、执行器与该世界的后台线程池。切换世界时整体替换：
 * 启动事件构造新实例并一次性发布，停止事件关停并丢弃。
 * <p>
 * 仓库初始化失败不会抛出：{@link #initErrorKey()} 非 {@code null} 表示后端当前不可用，
 * 此时 {@link #snapshotStore()} 与 {@link #blobStore()} 为 {@code null}，备份执行器
 * 返回 {@code NOT_INITIALIZED}，世界仍可正常进入。
 * <p>
 * 失败分两类：仓库被其它进程占用（{@link RepositoryBusyException}）时保留后台重试，
 * 由服务器线程每刻调用 {@link #tickBackendInit()} 驱动，打开成功后自动把协调器挂到
 * 执行器上；仓库损坏、权限或空间不足则判定为不可恢复，本次运行内不再重试。
 * <p>
 * 不引用 NeoForge 类型，但依赖 Minecraft 服务器对象，因此不纳入单元测试；
 * 路径规则见 {@link RepositoryLayout}。
 */
public final class WorldRuntime {

    /** 仓库初始化失败时向玩家提示的翻译键。 */
    public static final String INIT_ERROR_KEY = "foldbackup.command.backend_init_failed";
    /** 仓库只是被另一进程占用、仍在后台重试时向玩家提示的翻译键。 */
    public static final String BUSY_ERROR_KEY = "foldbackup.command.backend_busy";

    /** 仓库被占用时的重试间隔。 */
    private static final long RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static final LogUtil LOG = LogUtil.getLogger(WorldRuntime.class);

    private final MinecraftServer server;
    private final Path repoRoot;
    private final Path worldSavePath;
    private final String worldName;
    private final TaskRegistry taskRegistry;
    private final FoldBackupConfig config;
    private final WorldAccessor worldAccessor;

    private volatile String initErrorKey;
    private volatile SnapshotStore snapshotStore;
    private volatile BlobStore blobStore;
    private volatile BackupScheduler scheduler;
    private volatile BackupExecutor executor;
    private volatile ExecutorService backupExecutor;

    /** 仓库已打开，且协调器已挂到执行器上。 */
    private volatile boolean repositoryReady;
    /** 仓库打不开且重试没有意义（损坏、权限、空间不足等）。 */
    private volatile boolean repositoryFailurePermanent;
    /** 下一次后台重试的时刻，{@link System#nanoTime()} 时间系。 */
    private volatile long nextRepositoryRetryNanos;
    private final AtomicBoolean repositoryRetryInFlight = new AtomicBoolean();
    /** 持久化计时状态已读取；只在服务器线程上写入。 */
    private boolean schedulerInitialized;
    /** 关停后不再发布仓库，也不再重试。 */
    private volatile boolean closed;

    /**
     * 构造并初始化世界运行时。
     *
     * @param server          当前服务器
     * @param baseRoot        备份根
     * @param worldSavePath   世界存档根目录
     * @param dedicatedServer 是否为专用服务器
     * @param config          配置
     * @param taskRegistry    共享的任务注册表
     */
    public WorldRuntime(MinecraftServer server, Path baseRoot, Path worldSavePath, boolean dedicatedServer,
            FoldBackupConfig config, TaskRegistry taskRegistry) {
        this.server = server;
        this.worldSavePath = worldSavePath;
        this.repoRoot = RepositoryLayout.repoRoot(baseRoot, worldSavePath, dedicatedServer);
        this.worldName = RepositoryLayout.worldName(worldSavePath);
        this.taskRegistry = taskRegistry;
        this.config = config;
        this.scheduler = new BackupScheduler(config);
        this.backupExecutor = newWorkerPool();
        this.worldAccessor = new WorldAccessor(server);
        // 协调器要等仓库打开后才存在；被占用时由 tickBackendInit 在后台补挂。
        this.executor = new BackupExecutor(server, worldAccessor, null, config, scheduler,
                backupExecutor, taskRegistry);

        try {
            initializeRepository();
            initSchedulerOnce();
        } catch (RepositoryBusyException e) {
            LOG.warn("Backup repository at {} is in use by another process; "
                    + "backups will keep retrying in the background", repoRoot, e);
            initErrorKey = BUSY_ERROR_KEY;
            nextRepositoryRetryNanos = System.nanoTime() + RETRY_INTERVAL_NANOS;
        } catch (IOException | RuntimeException e) {
            markRepositoryFailed(e);
        }
    }

    /**
     * 服务器线程每刻调用：仓库因被另一进程占用而没打开时，在后台线程上重试。
     * <p>
     * 已就绪时只补读一次计时状态，之后每刻只是一次 volatile 读；
     * 已判定不可恢复或已关停时直接返回。
     */
    public void tickBackendInit() {
        if (repositoryReady) {
            // 后台重试成功后的第一个刻：计时状态在服务器线程补读，避免跨线程写入。
            initSchedulerOnce();
            return;
        }
        if (repositoryFailurePermanent || closed)
            return;
        if (System.nanoTime() - nextRepositoryRetryNanos < 0L)
            return;
        if (!repositoryRetryInFlight.compareAndSet(false, true))
            return;

        ExecutorService pool;
        synchronized (this) {
            pool = closed ? null : backupExecutor;
        }
        if (pool == null) {
            repositoryRetryInFlight.set(false);
            return;
        }
        try {
            pool.execute(this::retryRepositoryInit);
        } catch (RuntimeException e) {
            repositoryRetryInFlight.set(false);
            LOG.warn("Unable to schedule a backup repository retry", e);
        }
    }

    /** 后台线程：重试打开仓库；成功即发布，失败则按可恢复性决定继续等还是放弃。 */
    private void retryRepositoryInit() {
        try {
            initializeRepository();
        } catch (RepositoryBusyException e) {
            LOG.warn("Backup repository at {} is still in use by another process; retrying in {}s",
                    repoRoot, TimeUnit.NANOSECONDS.toSeconds(RETRY_INTERVAL_NANOS));
        } catch (IOException | RuntimeException e) {
            markRepositoryFailed(e);
        } finally {
            repositoryRetryInFlight.set(false);
            nextRepositoryRetryNanos = System.nanoTime() + RETRY_INTERVAL_NANOS;
        }
    }

    /**
     * 打开仓库、构造协调器并发布。只在构造与后台重试时调用，可重复执行。
     *
     * @throws RepositoryBusyException 仓库正被另一方占用，可重试
     */
    private void initializeRepository() throws IOException {
        // 仓库级副作用：只作用于本仓库，避免清理到其它存档的 .wip
        new BlobMaintenance(repoRoot).cleanupWipFiles();
        BlobStore blobs = new BlobStore(repoRoot, config.getCompressionLevel());
        SnapshotStore store = new SnapshotStore(repoRoot);
        var regionProcessor = new RegionProcessor(store::getIndex,
                blobs, config.getCompressionLevel(), config.buildBackupFilter(),
                config.getRegionThreads(), config.getRegionMemoryLimitMB());
        BackupCoordinator coordinator = new BackupCoordinator(
                new BackupContext(worldSavePath, worldName, ""),
                store, regionProcessor, config.buildRetentionPolicy());
        publishRepository(store, blobs, coordinator);
    }

    /** 发布仓库句柄并把协调器挂到执行器上；关停中的发布直接丢弃。 */
    private void publishRepository(SnapshotStore store, BlobStore blobs, BackupCoordinator coordinator) {
        synchronized (this) {
            if (closed)
                return;
            snapshotStore = store;
            blobStore = blobs;
            // 与仓库句柄同一个发布点：看到快照仓库的调用方一定也能启动备份。
            if (executor != null)
                executor.attachCoordinator(coordinator);
            repositoryReady = true;
            initErrorKey = null;
        }
        LOG.info("FoldBackup initialized. Backup store: {}", repoRoot);
    }

    /**
     * 读取持久化的计时状态；只在服务器线程上执行一次。
     * <p>
     * 仓库被占用时构造期读不到，重试成功后的第一个刻补读；重试不会覆盖已经
     * 累积的在线时间。必须在任何 {@code triggerBackup} 之前完成，否则
     * {@code backupOnStart} 会被最小间隔误判为过快。
     */
    private void initSchedulerOnce() {
        if (schedulerInitialized)
            return;
        BackupScheduler current = scheduler;
        if (current != null)
            current.init(repoRoot);
        schedulerInitialized = true;
    }

    /** 仓库打不开且重试无意义：停止重试并给出持久提示。 */
    private void markRepositoryFailed(Exception e) {
        if (closed) {
            // 关停打断的重试不算失败，别再报“备份不可用”。
            LOG.debug("Repository retry ended during shutdown: {}", e.getMessage());
            return;
        }
        LOG.error("Failed to initialize backup repository at {}; backups are unavailable for this world",
                repoRoot, e);
        synchronized (this) {
            blobStore = null;
            snapshotStore = null;
            repositoryFailurePermanent = true;
            initErrorKey = INIT_ERROR_KEY;
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
     * <p>
     * 先中止仍在进行的备份、再恢复世界保存：备份线程可能还持有仓库写锁并继续读世界目录，
     * 顺序反过来会让世界在扫描期间重新开始写入。无论是否有备份在运行，关停都不能把
     * {@code noSave} 留在关闭状态。
     */
    public void shutdown() {
        ExecutorService pool;
        BackupExecutor running;
        synchronized (this) {
            closed = true;
            pool = backupExecutor;
            running = executor;
            backupExecutor = null;
            executor = null;
            scheduler = null;
            snapshotStore = null;
            blobStore = null;
        }
        if (running != null) {
            running.cancelForShutdown();
        }
        try {
            worldAccessor.enableSaving();
        } catch (RuntimeException e) {
            LOG.error("Failed to restore world saving while shutting down the world runtime", e);
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

    /** 后端当前不可用时的提示键（仓库被占用重试中或已判定不可恢复）；正常为 {@code null}。 */
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
