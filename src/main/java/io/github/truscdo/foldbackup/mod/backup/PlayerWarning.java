package io.github.truscdo.foldbackup.mod.backup;

/**
 * 向在线玩家广播一条本地化提示。
 * <p>
 * 实现必须运行在服务器线程上；{@link BackupExecutor} 的所有调用点都满足这一前提。
 */
@FunctionalInterface
interface PlayerWarning {

    /**
     * @param translationKey {@code assets/foldbackup/lang} 中的键
     * @param args           格式化参数，可为空
     */
    void send(String translationKey, Object... args);
}
