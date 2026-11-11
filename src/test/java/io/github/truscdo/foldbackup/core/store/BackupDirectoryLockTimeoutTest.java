package io.github.truscdo.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 仓库写锁的等待上限：另一个线程持锁时必须失败，而不是无限阻塞。 */
class BackupDirectoryLockTimeoutTest {

    @TempDir
    Path tempDir;

    @Test
    void contendedWriterFailsWithinTheTimeout() throws Exception {
        SnapshotStore holder = new SnapshotStore(tempDir, new IndexStore(tempDir.resolve("index.db")),
                new BlobMaintenance(tempDir), 5_000L);
        SnapshotStore waiter = new SnapshotStore(tempDir, new IndexStore(tempDir.resolve("index.db")),
                new BlobMaintenance(tempDir), 200L);

        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holding = new Thread(() -> {
            try (SnapshotStore.WriteSession ignored = holder.beginWrite()) {
                acquired.countDown();
                release.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "lock-holder");
        holding.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "The first writer must acquire the lock");
        try {
            long start = System.nanoTime();
            // 必须区分为“仓库被占用（可重试）”，而不是笼统的 IO 失败。
            assertThrows(RepositoryBusyException.class, waiter::beginWrite);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis >= 150L, "The writer must have waited for the timeout, waited " + elapsedMillis + "ms");
        } finally {
            release.countDown();
            holding.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(holding.isAlive());
        }
        // 锁释放后可以再次获取。
        try (SnapshotStore.WriteSession ignored = waiter.beginWrite()) {
            // no-op
        }
    }
}
