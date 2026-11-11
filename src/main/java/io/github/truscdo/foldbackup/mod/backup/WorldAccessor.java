package io.github.truscdo.foldbackup.mod.backup;

import io.github.truscdo.foldbackup.core.LogUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理 Minecraft 世界保存状态以确保备份安全。
 * <p>
 * 提供：
 * <ul>
 * <li>禁用/启用所有维度的自动保存</li>
 * <li>强制将所有区块刷新到磁盘</li>
 * </ul>
 */
public class WorldAccessor {

    private static final LogUtil LOG = LogUtil.getLogger("foldbackup-world");

    private final MinecraftServer server;
    private final List<Runnable> savingRestorations = new ArrayList<>();

    interface SavingLevel {
        boolean noSave();
        void noSave(boolean value);
    }

    Iterable<SavingLevel> savingLevels() {
        List<SavingLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level != null) levels.add(new SavingLevel() {
                public boolean noSave() { return level.noSave; }
                public void noSave(boolean value) { level.noSave = value; }
            });
        }
        return levels;
    }

    public WorldAccessor(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 禁用所有维度的自动保存，以防止备份期间的写入。
     */
    public void disableSaving() {
        if (!savingRestorations.isEmpty())
            throw new IllegalStateException("World saving is already suspended");
        for (SavingLevel level : savingLevels()) {
            boolean previous = level.noSave();
            savingRestorations.add(() -> level.noSave(previous));
            level.noSave(true);
        }
        LOG.info("World saving disabled across all dimensions");
    }

    /**
     * 恢复本次暂停前各维度的保存状态；未暂停时不修改任何维度。
     * <p>
     * 单个维度恢复失败不会阻止其余维度恢复，也不会把暂停状态留在列表里。
     */
    public void enableSaving() {
        if (savingRestorations.isEmpty())
            return;
        List<Runnable> restorations = new ArrayList<>(savingRestorations);
        savingRestorations.clear();
        RuntimeException failure = null;
        for (Runnable restore : restorations) {
            try {
                restore.run();
            } catch (RuntimeException e) {
                if (failure == null)
                    failure = e;
                else
                    failure.addSuppressed(e);
            }
        }
        LOG.info("World saving state restored across all dimensions");
        if (failure != null)
            throw failure;
    }

    /**
     * 强制将所有区块保存到磁盘，并可选择刷新。
     *
     * @param flush 如果为 true，强制 OS 同步到物理存储
     */
    public void saveAll(boolean flush) {
        LOG.info("Forcing world save...");
        server.saveAllChunks(true, flush, true);
        LOG.info("World save complete");
    }
}
