package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link BackendGuard} 的快照 ID 格式校验、解析与加载错误映射测试。 */
class BackendGuardTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "foo", "S-123", "a/b", "S-20260902_12000", "S-20260902_1200001"})
    void resolveEpochRejectsInvalidSnapshotIdBeforeIndexLookup(String invalidId) throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir) {
            @Override
            public long resolveEpoch(String snapshotId) throws IOException {
                fail("Invalid snapshot ID must be rejected before calling SnapshotStore.resolveEpoch");
                return 0L;
            }
        };
        List<String> reported = new ArrayList<>();

        assertNull(BackendGuard.resolveEpoch(store, invalidId, (key, args) -> reported.add(key)));
        assertEquals(List.of("foldbackup.command.invalid_snapshot_id"), reported);
    }

    @Test
    void resolveEpochReportsNotFoundAndReturnsEpochWhenPresent() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260902_120000", 1_000L, "world"));

        List<String> missingReported = new ArrayList<>();
        assertNull(BackendGuard.resolveEpoch(store, "S-20260902_120001",
                (key, args) -> missingReported.add(key + ":" + args[0])));
        assertEquals(List.of("foldbackup.command.info.not_found:S-20260902_120001"), missingReported);

        List<String> successReported = new ArrayList<>();
        assertEquals(1_000L, BackendGuard.resolveEpoch(store, "S-20260902_120000",
                (key, args) -> successReported.add(key)));
        assertTrue(successReported.isEmpty());
    }

    @Test
    void requireSnapshotHandlesMissingAndConcurrentDeletionAndRethrowsUnexpectedErrors() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260902_120000", 1_000L, "world"));

        List<String> validReported = new ArrayList<>();
        SnapshotManifest loaded = BackendGuard.requireSnapshot(store, "S-20260902_120000",
                (key, args) -> validReported.add(key));
        assertNotNull(loaded);
        assertEquals("S-20260902_120000", loaded.getSnapshotId());
        assertTrue(validReported.isEmpty());

        // 模拟 resolveEpoch 成功但 loadSnapshot 时已被并发删除的窗口。
        SnapshotStore disappearingStore = new SnapshotStore(tempDir) {
            @Override
            public SnapshotManifest loadSnapshot(long epochSeconds) {
                return null;
            }
        };
        List<String> concurrentReported = new ArrayList<>();
        assertNull(BackendGuard.requireSnapshot(disappearingStore, "S-20260902_120000",
                (key, args) -> concurrentReported.add(key + ":" + args[0])));
        assertEquals(List.of("foldbackup.command.info.not_found:S-20260902_120000"), concurrentReported);

        SnapshotStore failingStore = new SnapshotStore(tempDir) {
            @Override
            public SnapshotManifest requireSnapshot(long epochSeconds, String snapshotId) throws IOException {
                throw new IOException("unexpected failure");
            }
        };
        List<String> errorReported = new ArrayList<>();
        assertThrows(IOException.class, () -> BackendGuard.requireSnapshot(
                failingStore, "S-20260902_120000", (key, args) -> errorReported.add(key)));
        assertTrue(errorReported.isEmpty());
    }
}
