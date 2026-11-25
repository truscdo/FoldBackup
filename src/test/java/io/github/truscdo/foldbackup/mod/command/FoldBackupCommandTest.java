package io.github.truscdo.foldbackup.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import io.github.truscdo.foldbackup.core.store.BlobMaintenance;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FoldBackupCommand} 快照详情查询、参数解析、翻译与 Brigadier 命令执行测试。 */
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
    void parseDeleteArgsSupportsDryRunAnywhereDeduplicatesAndRejectsInvalid() {
        var dryFirst = FoldBackupCommand.parseDeleteArgs("--dry-run S-20260901_100000 S-20260901_110000");
        assertTrue(dryFirst.valid());
        assertTrue(dryFirst.dryRun());
        assertEquals(List.of("S-20260901_100000", "S-20260901_110000"), dryFirst.snapshotIds());

        var dryLastDedup = FoldBackupCommand.parseDeleteArgs(
                "S-20260901_100000 S-20260901_110000 S-20260901_100000 --dry-run");
        assertTrue(dryLastDedup.valid());
        assertTrue(dryLastDedup.dryRun());
        assertEquals(List.of("S-20260901_100000", "S-20260901_110000"), dryLastDedup.snapshotIds());

        assertFalse(FoldBackupCommand.parseDeleteArgs("").valid());
        assertFalse(FoldBackupCommand.parseDeleteArgs("--dry-run").valid());
        assertFalse(FoldBackupCommand.parseDeleteArgs("--dry-run --dry-run S-20260901_100000").valid());
        assertFalse(FoldBackupCommand.parseDeleteArgs("--unknown S-20260901_100000").valid());
    }

    @Test
    void pinTranslationsExistAcrossAllSupportedLanguages() {
        List<String> keys = List.of(
                "foldbackup.command.delete.pinned",
                "foldbackup.command.delete.usage",
                "foldbackup.command.delete.dry_run.title",
                "foldbackup.command.delete.dry_run.item",
                "foldbackup.command.delete.dry_run.summary",
                "foldbackup.command.list.title",
                "foldbackup.command.list.title_paged",
                "foldbackup.command.list.summary_storage",
                "foldbackup.command.list.summary_storage_pinned",
                "foldbackup.command.list.summary_breakdown",
                "foldbackup.command.list.header",
                "foldbackup.command.list.row",
                "foldbackup.command.list.row_pinned",
                "foldbackup.command.list.row_hover",
                "foldbackup.command.list.page_out_of_range",
                "foldbackup.command.list.nav.prev",
                "foldbackup.command.list.nav.next",
                "foldbackup.command.list.nav.first_hover",
                "foldbackup.command.list.nav.last_hover",
                "foldbackup.command.list.nav.page_hover",
                "foldbackup.command.list.nav.console",
                "foldbackup.command.pin.success",
                "foldbackup.command.pin.already",
                "foldbackup.command.pin.failed",
                "foldbackup.command.unpin.success",
                "foldbackup.command.unpin.already",
                "foldbackup.command.unpin.failed",
                "foldbackup.command.info.pinned_yes",
                "foldbackup.command.info.pinned_no",
                "foldbackup.command.info.size",
                "foldbackup.command.info.new_data",
                "foldbackup.command.info.space",
                "foldbackup.backup.capacity_over_limit_pinned");
        for (String lang : List.of("en_us", "zh_cn", "es_es", "pt_br", "ru_ru")) {
            for (String key : keys) {
                String value = FoldBackupTranslations.textForLanguage(
                        lang, key, "S-20260902_120000", "1.0M", "2.0M", "3.0M", "4.0M", "5.0M");
                assertNotEquals(key, value, "Missing translation key " + key + " for language " + lang);
                assertFalse(value.isBlank(), "Blank translation key " + key + " for language " + lang);
            }
        }
    }

    @Test
    void pinAndUnpinCommandsPersistIdempotentlyAndReportFailures() throws Exception {
        SnapshotStore store = createSampleStore();
        TestFoldBackupMod mod = new TestFoldBackupMod(store);
        CommandDispatcher<CommandSourceStack> dispatcher = registerDispatcher(mod);
        RecordingSource source = new RecordingSource();

        // Pin unpinned snapshot -> success & broadcast
        assertEquals(1, dispatcher.execute("foldbackup pin S-20260901_100000", source));
        assertTrue(store.loadSnapshot(1000L).isPinned());
        assertTrue(source.lastSuccess().contains("Pinned snapshot: §fS-20260901_100000"), source.allOutput());
        assertTrue(source.lastSuccessLogged());
        assertTrue(source.failures.isEmpty());

        // Pin again -> idempotent "already pinned", not broadcast
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup pin S-20260901_100000", source));
        assertTrue(source.lastSuccess().contains("is already pinned"), source.allOutput());
        assertFalse(source.lastSuccessLogged());

        // Unpin -> success & broadcast
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup unpin S-20260901_100000", source));
        assertFalse(store.loadSnapshot(1000L).isPinned());
        assertTrue(source.lastSuccess().contains("Unpinned snapshot: §fS-20260901_100000"), source.allOutput());
        assertTrue(source.lastSuccessLogged());

        // Unpin again -> idempotent "not pinned"
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup unpin S-20260901_100000", source));
        assertTrue(source.lastSuccess().contains("is not pinned"), source.allOutput());
        assertFalse(source.lastSuccessLogged());

        // Async failure path reports pin.failed
        source.clear();
        mod.pinFailure = new IOException("disk error");
        assertEquals(1, dispatcher.execute("foldbackup pin S-20260901_100000", source));
        assertEquals(1, source.failures.size(), source.allOutput());
        assertTrue(source.failures.getFirst().contains("Failed to pin snapshot S-20260901_100000"),
                source.allOutput());
    }

    @Test
    void deleteCommandProtectsPinnedSnapshotsSupportsDryRunAndBatchDeletes() throws Exception {
        SnapshotStore store = createSampleStore();
        TestFoldBackupMod mod = new TestFoldBackupMod(store);
        CommandDispatcher<CommandSourceStack> dispatcher = registerDispatcher(mod);
        RecordingSource source = new RecordingSource();

        // Batch delete including a pinned snapshot is rejected up front without calling async delete
        assertEquals(0, dispatcher.execute("foldbackup delete S-20260901_100000 S-20260901_120000", source));
        assertEquals(0, mod.deleteAsyncCalls.get());
        assertEquals(1, source.failures.size(), source.allOutput());
        assertTrue(source.failures.getFirst().contains("is pinned"), source.allOutput());
        assertTrue(source.failures.getFirst().contains("/foldbackup unpin S-20260901_120000"), source.allOutput());
        assertEquals(3, store.loadAllSnapshots().size());

        // Dry-run including a pinned snapshot is also rejected
        source.clear();
        assertEquals(0, dispatcher.execute("foldbackup delete --dry-run S-20260901_120000", source));
        assertEquals(1, source.failures.size(), source.allOutput());
        assertTrue(source.failures.getFirst().contains("is pinned"), source.allOutput());

        // Dry-run on two unpinned snapshots sharing P12 prints exact reclamation breakdown and keeps store untouched
        source.clear();
        assertEquals(1, dispatcher.execute(
                "foldbackup delete --dry-run S-20260901_100000 S-20260901_110000", source));
        assertEquals(0, mod.deleteAsyncCalls.get());
        assertTrue(source.failures.isEmpty(), source.allOutput());
        String dryRunOut = String.join("\n", source.successes);
        assertTrue(dryRunOut.contains("Deletion Space Estimate (2 snapshot(s), dry-run)"), dryRunOut);
        assertTrue(dryRunOut.contains("S-20260901_100000") && dryRunOut.contains("10.0M"), dryRunOut);
        assertTrue(dryRunOut.contains("S-20260901_110000") && dryRunOut.contains("20.0M"), dryRunOut);
        assertTrue(dryRunOut.contains("Unique sum: §f30.0M"), dryRunOut);
        assertTrue(dryRunOut.contains("Extra shared freed: §f15.0M"), dryRunOut);
        assertTrue(dryRunOut.contains("Total reclaim: §a45.0M"), dryRunOut);
        assertEquals(3, store.loadAllSnapshots().size());

        // Concurrent pin race during async delete maps SnapshotPinnedException to delete.pinned
        source.clear();
        mod.deleteAsyncOverride = epochs -> CompletableFuture.failedFuture(
                new SnapshotStore.SnapshotPinnedException("S-20260901_110000"));
        assertEquals(1, dispatcher.execute("foldbackup delete S-20260901_100000 S-20260901_110000", source));
        assertEquals(1, mod.deleteAsyncCalls.get());
        assertEquals(1, source.failures.size(), source.allOutput());
        assertTrue(source.failures.getFirst().contains("S-20260901_110000§c is pinned"), source.allOutput());
        assertTrue(source.failures.getFirst().contains("/foldbackup unpin S-20260901_110000"), source.allOutput());
        mod.deleteAsyncOverride = null;

        // Real batch deletion removes both unpinned snapshots and preserves pinned S-20260901_120000
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup delete S-20260901_100000 S-20260901_110000", source));
        assertEquals(2, mod.deleteAsyncCalls.get());
        assertTrue(source.failures.isEmpty(), source.allOutput());
        String deleteOut = String.join("\n", source.successes);
        assertTrue(deleteOut.contains("Deleting snapshot"), deleteOut);
        assertTrue(deleteOut.contains("Snapshot deleted: §fS-20260901_100000, S-20260901_110000"), deleteOut);
        assertEquals(List.of("S-20260901_120000"), store.listSnapshots());
    }

    @Test
    void listAndInfoCommandsDisplayPinnedStateSummaryAndSpaceBreakdown() throws Exception {
        SnapshotStore store = createSampleStore();
        TestFoldBackupMod mod = new TestFoldBackupMod(store);
        CommandDispatcher<CommandSourceStack> dispatcher = registerDispatcher(mod);
        RecordingSource source = new RecordingSource();

        // /foldbackup list with 1 pinned snapshot
        assertEquals(1, dispatcher.execute("foldbackup list", source));
        assertTrue(source.failures.isEmpty(), source.allOutput());
        String listWithPin = String.join("\n", source.successes);
        assertTrue(listWithPin.contains("Pinned: §e1 §7(Unique: 5.0M)"), listWithPin);
        assertTrue(listWithPin.contains("Unique: §e35.0M §7| Shared: §f115.0M §7(All: 100.0M)"), listWithPin);
        assertFalse(listWithPin.contains("Page "), "single page must not include Page suffix: " + listWithPin);
        assertFalse(listWithPin.contains("to navigate"), "single page must not include nav hint: " + listWithPin);
        String pinnedRow = source.successes.stream()
                .filter(line -> line.contains("S-20260901_120000"))
                .findFirst().orElseThrow();
        assertTrue(pinnedRow.endsWith("| §e[PIN]"), pinnedRow);

        // /foldbackup info on pinned vs unpinned snapshot
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup info S-20260901_120000", source));
        String pinnedInfo = String.join("\n", source.successes);
        assertTrue(pinnedInfo.contains("Pinned: §eYes"), pinnedInfo);
        assertTrue(pinnedInfo.contains("5.0M §7unique"), pinnedInfo);
        assertTrue(pinnedInfo.contains("100.0M §7shared with others (global: 100.0M)"), pinnedInfo);

        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup info S-20260901_100000", source));
        String unpinnedInfo = String.join("\n", source.successes);
        assertTrue(unpinnedInfo.contains("Pinned: §fNo"), unpinnedInfo);
        assertTrue(unpinnedInfo.contains("10.0M §7unique"), unpinnedInfo);
        assertTrue(unpinnedInfo.contains("115.0M §7shared with others (global: 100.0M)"), unpinnedInfo);

        // Unpin all -> list uses unpinned summary variant without [PIN]
        assertTrue(store.setPinned(3000L, false));
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup list", source));
        String listWithoutPin = String.join("\n", source.successes);
        assertTrue(listWithoutPin.contains("Pinned: §f0"), listWithoutPin);
        assertFalse(listWithoutPin.contains("(Unique:"), listWithoutPin);
        assertFalse(listWithoutPin.contains("[PIN]"), listWithoutPin);
    }

    @Test
    void listCommandPaginatesSuggestsPagesAndRejectsOutOfRange() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        long mb = 1024L * 1024L;
        Map<String, Long> sizes = Map.of("B", mb);
        for (int i = 1; i <= 25; i++) {
            String id = String.format("S-20260901_%06d", i);
            store.saveSnapshot(snapshot(id, i * 1000L, sizes, "B"));
        }

        TestFoldBackupMod mod = new TestFoldBackupMod(store);
        CommandDispatcher<CommandSourceStack> dispatcher = registerDispatcher(mod);
        RecordingSource source = new RecordingSource();

        // Page 1 (default): newest 10 snapshots (25..16)
        assertEquals(1, dispatcher.execute("foldbackup list", source));
        assertTrue(source.failures.isEmpty(), source.allOutput());
        String p1 = String.join("\n", source.successes);
        assertTrue(p1.contains("FoldBackup Snapshots (25, Page 1/3)"), p1);
        assertTrue(p1.contains("S-20260901_000025"), p1);
        assertTrue(p1.contains("S-20260901_000016"), p1);
        assertFalse(p1.contains("S-20260901_000015"), p1);
        assertTrue(p1.contains("Page §f1§7/§f3 §7(25 total)"), p1);

        // Page 2: middle 10 snapshots (15..6)
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup list 2", source));
        String p2 = String.join("\n", source.successes);
        assertTrue(p2.contains("FoldBackup Snapshots (25, Page 2/3)"), p2);
        assertTrue(p2.contains("S-20260901_000015"), p2);
        assertTrue(p2.contains("S-20260901_000006"), p2);
        assertFalse(p2.contains("S-20260901_000016"), p2);
        assertFalse(p2.contains("S-20260901_000005"), p2);

        // Page 3: remaining 5 snapshots (5..1)
        source.clear();
        assertEquals(1, dispatcher.execute("foldbackup list 3", source));
        String p3 = String.join("\n", source.successes);
        assertTrue(p3.contains("FoldBackup Snapshots (25, Page 3/3)"), p3);
        assertTrue(p3.contains("S-20260901_000005"), p3);
        assertTrue(p3.contains("S-20260901_000001"), p3);
        assertFalse(p3.contains("S-20260901_000006"), p3);

        // Page 4: out of range
        source.clear();
        assertEquals(0, dispatcher.execute("foldbackup list 4", source));
        assertTrue(source.successes.isEmpty(), source.allOutput());
        assertEquals(1, source.failures.size(), source.allOutput());
        assertTrue(source.failures.getFirst().contains("Page 4 is out of range (valid range: 1-3)."),
                source.allOutput());

        // Tab suggestions for page argument
        var suggestions = dispatcher.getCompletionSuggestions(
                dispatcher.parse("foldbackup list ", source)).get();
        assertEquals(List.of("1", "2", "3"),
                suggestions.getList().stream().map(Suggestion::getText).toList());
    }

    private SnapshotStore createSampleStore() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        long mb = 1024L * 1024L;
        Map<String, Long> sizes = Map.of(
                "ALL", 100 * mb,
                "U1", 10 * mb,
                "U2", 20 * mb,
                "P12", 15 * mb,
                "U3", 5 * mb
        );
        store.saveSnapshot(snapshot("S-20260901_100000", 1000L, sizes, "ALL", "U1", "P12"));
        store.saveSnapshot(snapshot("S-20260901_110000", 2000L, sizes, "ALL", "U2", "P12"));
        store.saveSnapshot(snapshot("S-20260901_120000", 3000L, sizes, "ALL", "U3"));
        store.setPinned(3000L, true);
        return store;
    }

    private static SnapshotManifest snapshot(String id, long epoch, Map<String, Long> sizes, String... blobPaths) {
        SnapshotManifest manifest = new SnapshotManifest(id, epoch, "world");
        long total = 0L;
        for (int i = 0; i < blobPaths.length; i++) {
            String path = blobPaths[i];
            long size = sizes.get(path);
            manifest.addExtraFile(new FileEntry("f" + i + "-" + path, "h-" + path, path, size, size * 2));
            total += size;
        }
        manifest.getSummary().setExtraFilesTotal(blobPaths.length);
        manifest.getSummary().setTotalCompressedBytes(total);
        manifest.getSummary().setTotalUncompressedBytes(total * 2);
        return manifest;
    }

    private static CommandDispatcher<CommandSourceStack> registerDispatcher(FoldBackupMod mod) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        FoldBackupCommand.register(dispatcher, mod);
        return dispatcher;
    }

    private static final class TestConfig extends FoldBackupConfig {
        private final String defaultLanguage;

        TestConfig(String defaultLanguage) {
            this.defaultLanguage = defaultLanguage;
        }

        @Override
        public String getDefaultLanguage() {
            return defaultLanguage;
        }

        @Override
        public boolean isAutoDetectPlayerLanguage() {
            return false;
        }
    }

    private static final class TestFoldBackupMod extends FoldBackupMod {
        private final SnapshotStore store;
        private final AtomicInteger deleteAsyncCalls = new AtomicInteger();
        private Throwable pinFailure;
        private Function<Collection<Long>, CompletableFuture<BlobMaintenance.GcResult>> deleteAsyncOverride;

        TestFoldBackupMod(SnapshotStore store) {
            super(new TestConfig("en_us"));
            this.store = store;
        }

        @Override
        public SnapshotStore getSnapshotStore() {
            return store;
        }

        @Override
        public void executeOnServer(Runnable task) {
            task.run();
        }

        @Override
        public CompletableFuture<Boolean> setPinnedAsync(long epochSeconds, boolean pinned) {
            if (pinFailure != null) {
                return CompletableFuture.failedFuture(pinFailure);
            }
            try {
                return CompletableFuture.completedFuture(store.setPinned(epochSeconds, pinned));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<BlobMaintenance.GcResult> deleteSnapshotsAsync(Collection<Long> epochSeconds) {
            deleteAsyncCalls.incrementAndGet();
            if (deleteAsyncOverride != null) {
                return deleteAsyncOverride.apply(epochSeconds);
            }
            try {
                return CompletableFuture.completedFuture(store.deleteSnapshots(epochSeconds));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private static final class RecordingSource extends CommandSourceStack {
        final List<String> successes = new ArrayList<>();
        final List<Boolean> successLogged = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        RecordingSource() {
            super(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 2,
                    "test", Component.literal("test"), null, null);
        }

        @Override
        public void sendSuccess(Supplier<Component> messageSupplier, boolean allowLogging) {
            successes.add(messageSupplier.get().getString());
            successLogged.add(allowLogging);
        }

        @Override
        public void sendFailure(Component message) {
            failures.add(message.getString());
        }

        String lastSuccess() {
            return successes.getLast();
        }

        boolean lastSuccessLogged() {
            return successLogged.getLast();
        }

        void clear() {
            successes.clear();
            successLogged.clear();
            failures.clear();
        }

        String allOutput() {
            return "successes=" + successes + ", failures=" + failures;
        }
    }
}
