package io.github.truscdo.foldbackup.core.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM 内串行化与跨进程排他锁。锁文件不能删除，否则可能锁住不同 inode。
 * <p>
 * 等待有上限：等待超时抛出 {@link RepositoryBusyException}，调用方据此失败本次操作，
 * 而不是无限期阻塞；被中断或嵌套不同仓库实例则是普通 {@link IOException}。
 * 备份期间世界保存已经暂停，在锁上无限等待会把保存关闭时间一起拖长。
 */
final class BackupDirectoryLock {
    private static final ConcurrentHashMap<Path, BackupDirectoryLock> LOCKS = new ConcurrentHashMap<>();
    /** 默认锁等待上限：超过这个时间按“仓库被占用”处理。 */
    static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 50L;

    private final ReentrantLock mutex = new ReentrantLock();
    private final Path path;
    private FileChannel channel;
    private FileLock fileLock;
    private Object owner;

    private BackupDirectoryLock(Path root) {
        path = root.resolve("index.db.lock");
    }

    static BackupDirectoryLock forRoot(Path root) throws IOException {
        Files.createDirectories(root);
        return LOCKS.computeIfAbsent(root.toRealPath(), BackupDirectoryLock::new);
    }

    /**
     * 获取仓库写锁，最多等待 {@code timeoutMillis}。
     *
     * @param timeoutMillis 等待上限；非正数表示只尝试一次
     * @throws RepositoryBusyException 等待超时，仓库正被另一方占用（可重试）
     * @throws IOException             被中断，或已有其它仓库实例嵌套持锁
     */
    void lock(Object requestedOwner, long timeoutMillis) throws IOException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        acquireMutex(timeoutMillis);
        if (mutex.getHoldCount() > 1) {
            if (owner != requestedOwner) {
                mutex.unlock();
                throw new IOException("Cannot nest different stores for backup directory: " + path.getParent());
            }
            return;
        }
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = acquireFileLock(deadlineNanos);
            owner = requestedOwner;
        } catch (IOException | RuntimeException e) {
            try {
                if (channel != null) channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            } finally {
                channel = null;
                mutex.unlock();
            }
            throw e;
        }
    }

    private void acquireMutex(long timeoutMillis) throws IOException {
        try {
            if (!mutex.tryLock(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS))
                throw new RepositoryBusyException("Timed out after " + timeoutMillis
                        + " ms waiting for the backup directory lock: " + path.getParent());
        } catch (InterruptedException e) {
            throw interrupted(e);
        }
    }

    /** 等待锁时被中断：恢复中断标志，把中断转成调用方能处理的失败。 */
    private IOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        return new IOException("Interrupted while waiting for the backup directory lock: "
                + path.getParent(), cause);
    }

    /** 跨进程锁无法设超时，用 tryLock 轮询实现一个有界的等待。 */
    private FileLock acquireFileLock(long deadlineNanos) throws IOException {
        while (true) {
            FileLock acquired = null;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同一 JVM 的其它通道持有该文件的锁；按“被占用”继续等待。
            }
            if (acquired != null)
                return acquired;
            if (System.nanoTime() - deadlineNanos >= 0L)
                throw new RepositoryBusyException(
                        "Backup directory is locked by another process: " + path.getParent());
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                throw interrupted(e);
            }
        }
    }

    void unlock() throws IOException {
        try {
            if (mutex.getHoldCount() == 1) {
                try {
                    fileLock.release();
                } finally {
                    channel.close();
                    fileLock = null;
                    channel = null;
                    owner = null;
                }
            }
        } finally {
            mutex.unlock();
        }
    }
}
