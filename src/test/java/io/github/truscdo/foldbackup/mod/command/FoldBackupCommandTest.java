package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FoldBackupCommand} 快照详情查询错误映射测试。 */
class FoldBackupCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSnapshotForInfoMapsInvalidMissingAndUnexpectedFailureConsistently() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260902_120000", 1_000L, "world"));

        List<String> invalidReported = new ArrayList<>();
        assertNull(FoldBackupCommand.loadSnapshotForInfo(
                store, "not-a-snapshot-id", (key, args) -> invalidReported.add(key)));
        assertEquals(List.of("foldbackup.command.invalid_snapshot_id"), invalidReported);

        List<String> missingReported = new ArrayList<>();
        assertNull(FoldBackupCommand.loadSnapshotForInfo(
                store, "S-20260902_120001", (key, args) -> missingReported.add(key + ":" + args[0])));
        assertEquals(List.of("foldbackup.command.info.not_found:S-20260902_120001"), missingReported);

        List<String> okReported = new ArrayList<>();
        SnapshotManifest manifest = FoldBackupCommand.loadSnapshotForInfo(
                store, "S-20260902_120000", (key, args) -> okReported.add(key));
        assertNotNull(manifest);
        assertEquals(1_000L, manifest.getEpochSeconds());
        assertTrue(okReported.isEmpty());

        SnapshotStore brokenStore = new SnapshotStore(tempDir) {
            @Override
            public SnapshotManifest requireSnapshot(long epochSeconds, String snapshotId) throws IOException {
                throw new IOException("corrupted state");
            }
        };
        List<String> failedReported = new ArrayList<>();
        assertNull(FoldBackupCommand.loadSnapshotForInfo(
                brokenStore, "S-20260902_120000", (key, args) -> failedReported.add(key + ":" + args[0])));
        assertEquals(List.of("foldbackup.command.info.failed:S-20260902_120000"), failedReported);
    }

    @Test
    void pinTranslationsExistAcrossAllSupportedLanguages() {
        List<String> keys = List.of(
                "foldbackup.command.delete.pinned",
                "foldbackup.command.pin.success",
                "foldbackup.command.pin.already",
                "foldbackup.command.pin.failed",
                "foldbackup.command.unpin.success",
                "foldbackup.command.unpin.already",
                "foldbackup.command.unpin.failed",
                "foldbackup.command.info.pinned_yes",
                "foldbackup.command.info.pinned_no",
                "foldbackup.backup.capacity_over_limit_pinned");
        for (String lang : List.of("en_us", "zh_cn", "es_es", "pt_br", "ru_ru")) {
            for (String key : keys) {
                String value = FoldBackupTranslations.textForLanguage(lang, key, "S-20260902_120000", "1.0 MB", 2);
                assertNotEquals(key, value, "Missing translation key " + key + " for language " + lang);
                assertFalse(value.isBlank(), "Blank translation key " + key + " for language " + lang);
            }
        }
    }
}
