package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import net.minecraft.commands.CommandSourceStack;

import java.io.IOException;

/**
 * 命令层对备份后端的检查与查询。
 * <p>
 * 所有方法形状一致：检查 → 失败则提示 → 返回空。调用方拿到非空结果后必须全程复用，
 * 因为后端可能随时被关停，二次取值会读到 {@code null}。
 */
public final class BackendGuard {

    /** 后端暂不可用时统一使用的提示键。 */
    private static final String NOT_READY_KEY = "foldbackup.command.backend_not_ready";

    @FunctionalInterface
    public interface FailureReporter {
        void report(String translationKey, Object... args);
    }

    private BackendGuard() {
    }

    /**
     * 取当前可用的快照仓库，不可用时通过 {@code reporter} 提示。
     */
    public static SnapshotStore requireSnapshotStore(FoldBackupMod mod, FailureReporter reporter) {
        SnapshotStore store = mod.getSnapshotStore();
        if (store == null) {
            reporter.report(NOT_READY_KEY);
        }
        return store;
    }

    static FailureReporter reporterFor(CommandSourceStack source, FoldBackupMod mod) {
        return (key, args) -> CommandFeedback.failure(source, mod, key, args);
    }

    /**
     * 取当前可用的快照仓库。
     *
     * @return 可用的仓库；不可用时已向来源报告，返回 {@code null}
     */
    static SnapshotStore requireSnapshotStore(CommandSourceStack source, FoldBackupMod mod) {
        return requireSnapshotStore(mod, reporterFor(source, mod));
    }

    /**
     * 取区块回档所需的后端句柄。
     *
     * @return 一次取到的仓库与 blob 存储；任一不可用时已向来源报告，返回 {@code null}
     */
    static RollbackHandles requireRollbackHandles(CommandSourceStack source, FoldBackupMod mod) {
        SnapshotStore store = mod.getSnapshotStore();
        BlobStore blobs = mod.getBlobStore();
        if (store == null || blobs == null) {
            CommandFeedback.failure(source, mod, NOT_READY_KEY);
            return null;
        }
        return new RollbackHandles(store, blobs);
    }

    /**
     * 校验快照 ID 格式并解析为 epoch 秒，格式无效或找不到时通过 {@code reporter} 提示。
     */
    public static Long resolveEpoch(SnapshotStore store, String id, FailureReporter reporter) {
        if (!SnapshotStore.isValidSnapshotId(id)) {
            reporter.report("foldbackup.command.invalid_snapshot_id");
            return null;
        }
        try {
            return store.resolveEpoch(id);
        } catch (IOException e) {
            reporter.report("foldbackup.command.info.not_found", id);
            return null;
        }
    }

    /**
     * 校验快照 ID 格式并解析为 epoch 秒。
     *
     * @return 解析结果；格式无效或找不到时已向来源报告，返回 {@code null}
     */
    static Long resolveEpoch(CommandSourceStack source, FoldBackupMod mod, SnapshotStore store, String id) {
        return resolveEpoch(store, id, reporterFor(source, mod));
    }

    /**
     * 校验快照 ID、解析 epoch 并加载清单；格式无效或找不到时通过 {@code reporter} 提示。
     *
     * @return 非空的快照清单；格式无效或找不到时已通过 {@code reporter} 报告并返回 {@code null}
     */
    public static SnapshotManifest requireSnapshot(SnapshotStore store, String id, FailureReporter reporter)
            throws IOException {
        Long epochSeconds = resolveEpoch(store, id, reporter);
        if (epochSeconds == null) {
            return null;
        }
        try {
            return store.requireSnapshot(epochSeconds, id);
        } catch (SnapshotStore.MissingSnapshotManifestException missing) {
            reporter.report("foldbackup.command.info.not_found", id);
            return null;
        }
    }

    /** 一次取值得到的回档后端句柄。 */
    record RollbackHandles(SnapshotStore store, BlobStore blobs) {
    }
}
