package io.github.truscdo.foldbackup.mod;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.task.TaskRegistry;
import io.github.truscdo.foldbackup.mod.backup.BackupExecutor;
import io.github.truscdo.foldbackup.mod.backup.BackupProgressNotifier;
import io.github.truscdo.foldbackup.mod.backup.BackupScheduler;
import io.github.truscdo.foldbackup.mod.backup.TickHandler;
import io.github.truscdo.foldbackup.mod.command.CommandRegistrationHandler;
import io.github.truscdo.foldbackup.mod.config.ConfigEventHandler;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.preview.PreviewData;
import io.github.truscdo.foldbackup.mod.preview.PreviewManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * FoldBackup 模组的主入口点——将后端、调度器和执行器连接在一起，
 * 并将 Minecraft 事件路由到专用处理类。
 * <p>
 * 路径解析分两段：备份根由配置幂等解析（{@link #bootstrapStorage()}），
 * 世界仓库根在每次服务器启动时按当前存档现算，并封装在 {@link WorldRuntime} 中。
 * <p>
 * 仓库初始化失败不会阻止服务器启动：{@link #getBackendErrorKey()} 非 {@code null} 时
 * 表示该世界的备份不可用，命令返回后端未就绪提示。
 */
@Mod(FoldBackupMod.MOD_ID)
public class FoldBackupMod {

    public static final String MOD_ID = "foldbackup";
    public static final LogUtil LOG = LogUtil.getLogger(MOD_ID);

    /** 备份根（所有世界的备份根），由配置派生。 */
    private volatile Path baseRoot;
    /** 当前世界的运行时；未启动或已停止时为 {@code null}。 */
    private volatile WorldRuntime runtime;
    private MinecraftServer server;
    private final FoldBackupConfig config;
    private final TaskRegistry taskRegistry = new TaskRegistry();
    private final BackupProgressNotifier progressNotifier = new BackupProgressNotifier();
    private final PreviewManager previewManager = new PreviewManager(this);

    public FoldBackupMod(IEventBus modBus, ModContainer container) {
        config = new FoldBackupConfig(container);

        // 注册专用处理类——每个类处理一个领域
        modBus.register(new ConfigEventHandler(this::bootstrapStorage));

        var gameBus = NeoForge.EVENT_BUS;
        gameBus.register(new ServerLifecycleHandler(this));
        gameBus.register(new PlayerLoginHandler(this));
        gameBus.register(new TickHandler(this));
        gameBus.register(new CommandRegistrationHandler(this));
        gameBus.register(previewManager);
    }

    // --- 基路径 ---

    /**
     * 幂等解析备份根。配置重载时仍是空操作：基路径只在首次解析，
     * 与"本次不做配置热重载"保持一致。
     */
    void bootstrapStorage() {
        if (baseRoot != null) {
            return;
        }
        Path configured = config.getBackupRoot();
        baseRoot = configured.isAbsolute() ? configured : FMLPaths.GAMEDIR.get().resolve(configured);
        try {
            Files.createDirectories(baseRoot);
        } catch (IOException e) {
            LOG.error("Failed to create backup root: {}", baseRoot, e);
        }
    }

    // --- 世界运行时 ---

    /**
     * 为给定世界构造并发布运行时。若存在旧实例，先关停（事件丢失时的兜底）。
     */
    void initializeBackend(Path worldSavePath) {
        bootstrapStorage();

        WorldRuntime previous;
        synchronized (this) {
            previous = runtime;
            runtime = null;
        }
        if (previous != null) {
            previous.shutdown();
        }

        WorldRuntime created = new WorldRuntime(server, baseRoot, worldSavePath,
                server.isDedicatedServer(), config,
                FMLPaths.GAMEDIR.get().resolve("mods"), taskRegistry);
        synchronized (this) {
            runtime = created;
        }
    }

    /**
     * 关停指定运行时并清空引用；仅当它仍是当前实例时生效。
     */
    void shutdownBackend(WorldRuntime expected) {
        synchronized (this) {
            if (runtime != expected) {
                return;
            }
            runtime = null;
        }
        expected.shutdown();
    }

    void setServer(MinecraftServer server) {
        this.server = server;
    }

    // --- 公共 API（供命令/调度器使用） ---

    WorldRuntime runtime() {
        return runtime;
    }

    public BackupExecutor getExecutor() {
        WorldRuntime current = runtime;
        return current == null ? null : current.executor();
    }

    public BackupProgressNotifier getProgressNotifier() {
        return progressNotifier;
    }

    public TaskRegistry getTaskRegistry() {
        return taskRegistry;
    }

    public BackupScheduler getScheduler() {
        WorldRuntime current = runtime;
        return current == null ? null : current.scheduler();
    }

    public SnapshotStore getSnapshotStore() {
        WorldRuntime current = runtime;
        return current == null ? null : current.snapshotStore();
    }

    public BlobStore getBlobStore() {
        WorldRuntime current = runtime;
        return current == null ? null : current.blobStore();
    }

    /** 后端初始化失败的提示键；正常或尚未启动时为 {@code null}。 */
    public String getBackendErrorKey() {
        WorldRuntime current = runtime;
        return current == null ? null : current.initErrorKey();
    }

    /** 当前世界的仓库根；尚未启动时为 {@code null}。 */
    public Path getRepoRoot() {
        WorldRuntime current = runtime;
        return current == null ? null : current.repoRoot();
    }

    /**
     * 在备份执行器线程上异步删除快照。
     * 进度通过 {@link TaskRegistry} 追踪。
     *
     * @return 删除和 GC 完成时返回的 future
     */
    public CompletableFuture<Void> deleteSnapshotAsync(long epochSeconds) {
        WorldRuntime current = runtime;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Backend not initialized"));
        }
        return current.deleteSnapshotAsync(epochSeconds);
    }

    public FoldBackupConfig getConfig() {
        return config;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public PreviewManager getPreviewManager() {
        return previewManager;
    }

    public CompletableFuture<PreviewData.Raw> readPreviewAsync(ChunkPos pos, Long epochSeconds, String dimension) {
        WorldRuntime current = runtime;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Backend not initialized"));
        }
        return current.readPreviewAsync(pos, epochSeconds, dimension);
    }
}
