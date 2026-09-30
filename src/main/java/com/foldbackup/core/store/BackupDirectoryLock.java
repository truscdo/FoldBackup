package com.foldbackup.core.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** JVM 内串行化与跨进程排他锁。锁文件不能删除，否则可能锁住不同 inode。 */
final class BackupDirectoryLock {
    private static final ConcurrentHashMap<Path, BackupDirectoryLock> LOCKS = new ConcurrentHashMap<>();
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

    void lock(Object requestedOwner) throws IOException {
        mutex.lock();
        if (mutex.getHoldCount() > 1) {
            if (owner != requestedOwner) {
                mutex.unlock();
                throw new IOException("Cannot nest different stores for backup directory: " + path.getParent());
            }
            return;
        }
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = channel.lock();
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
