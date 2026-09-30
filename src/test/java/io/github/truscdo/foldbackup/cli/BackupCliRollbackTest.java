package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackCoordinator;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CLI rollback command infrastructure.
 * <p>
 * The CLI {@code rollback} subcommand delegates to
 * {@link ChunkRollbackCoordinator} and {@link ChunkRollbackSupport}.
 * Those components have their own comprehensive tests; here we verify
 * the wiring types are correct and accessible from the CLI package.
 */
class BackupCliRollbackTest {

    @Test
    void rollbackUsesChunkRollbackCoordinator() {
        var constructors = ChunkRollbackCoordinator.class.getConstructors();
        assertTrue(constructors.length > 0, "Coordinator must have public constructors");
        // 验证构造器签名：(SnapshotStore, BlobStore, int)
        var params = constructors[0].getParameterTypes();
        assertEquals(3, params.length, "Coordinator constructor should take 3 params");
    }

    @Test
    void rollbackUsesChunkRollbackSupport_tryLock() {
        // tryLockRegionFile 是公共静态方法——验证其可访问性
        try {
            var method = ChunkRollbackSupport.class.getMethod("tryLockRegionFile", java.nio.file.Path.class);
            assertNotNull(method);
            assertEquals(boolean.class, method.getReturnType());
        } catch (NoSuchMethodException e) {
            fail("tryLockRegionFile(Path) must exist as public static: " + e.getMessage());
        }
    }

    @Test
    void rollbackUsesChunkRollbackSupport_resolveChunk() {
        try {
            var method = ChunkRollbackSupport.class.getMethod("resolveChunk",
                    io.github.truscdo.foldbackup.core.store.SnapshotStore.class,
                    io.github.truscdo.foldbackup.core.store.BlobStore.class,
                    int.class, int.class, Long.class, String.class);
            assertNotNull(method);
            assertEquals(ChunkRollbackSupport.ResolvedSnapshot.class, method.getReturnType());
        } catch (NoSuchMethodException e) {
            fail("resolveChunk(...) must exist as public static: " + e.getMessage());
        }
    }
}
