package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.BlobStore;
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
final class BackendGuard {

    /** 后端暂不可用时统一使用的提示键。 */
    private static final String NOT_READY_KEY = "foldbackup.command.backend_not_ready";

    private BackendGuard() {
    }

    /**
     * 取当前可用的快照仓库。
     *
     * @return 可用的仓库；不可用时已向来源报告，返回 {@code null}
     */
    static SnapshotStore requireSnapshotStore(CommandSourceStack source, FoldBackupMod mod) {
        SnapshotStore store = mod.getSnapshotStore();
        if (store == null) {
            CommandFeedback.failure(source, mod, NOT_READY_KEY);
        }
        return store;
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
     * 校验快照 ID 的字符集。
     *
     * @return 合法时 {@code true}；不合法时已向来源报告，返回 {@code false}
     */
    static boolean requireValidSnapshotId(CommandSourceStack source, FoldBackupMod mod, String id) {
        if (id.matches("[A-Za-z0-9_-]+")) {
            return true;
        }
        CommandFeedback.failure(source, mod, "foldbackup.command.invalid_snapshot_id");
        return false;
    }

    /**
     * 把快照 ID 解析为 epoch 秒。
     *
     * @return 解析结果；找不到时已向来源报告，返回 {@code null}
     */
    static Long resolveEpoch(CommandSourceStack source, FoldBackupMod mod, SnapshotStore store, String id) {
        try {
            return store.resolveEpoch(id);
        } catch (IOException e) {
            CommandFeedback.failure(source, mod, "foldbackup.command.info.not_found", id);
            return null;
        }
    }

    /** 一次取值得到的回档后端句柄。 */
    record RollbackHandles(SnapshotStore store, BlobStore blobs) {
    }
}
