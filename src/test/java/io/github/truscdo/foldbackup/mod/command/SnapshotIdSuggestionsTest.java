package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotIdSuggestionsTest {
    @TempDir Path root;

    @Test
    void providerRegisteredBeforeInitializationUsesCurrentBackend() throws Exception {
        var backend = new AtomicReference<SnapshotStore>();
        var provider = SnapshotIdSuggestions.suggestAll(backend::get);
        assertTrue(provider.getSuggestions(null, new SuggestionsBuilder("", 0)).get().isEmpty());

        backend.set(store(List.of("S-20260912_120000")));
        assertEquals(List.of("S-20260912_120000"), provider
                .getSuggestions(null, new SuggestionsBuilder("", 0)).get().getList()
                .stream().map(Suggestion::getText).toList());

        backend.set(null);
        assertTrue(provider.getSuggestions(null, new SuggestionsBuilder("S", 0)).get().isEmpty());
    }

    @Test
    void emptyPrefixLimitsRecentCandidatesAndPrefixMatchingIgnoresCase() throws Exception {
        List<String> ids = IntStream.range(0, 25)
                .mapToObj(i -> "S-20260912_1200%02d".formatted(24 - i)).toList();
        var store = store(ids);
        var provider = SnapshotIdSuggestions.suggestAll(() -> store);
        var suggestions = provider.getSuggestions(null, new SuggestionsBuilder("", 0)).get();
        assertEquals(20, suggestions.getList().size());
        assertEquals(new java.util.HashSet<>(ids.subList(0, 20)), suggestions.getList()
                .stream().map(Suggestion::getText).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(ids.getFirst()), provider
                .getSuggestions(null, new SuggestionsBuilder("s-20260912_120024", 0)).get()
                .getList().stream().map(Suggestion::getText).toList());
        assertTrue(provider.getSuggestions(null, new SuggestionsBuilder("missing", 0)).get().isEmpty());
    }

    @Test
    void suggestForDeleteFiltersPinnedAndEnteredIdsAndAdvancesOffset() throws Exception {
        var realStore = new SnapshotStore(root);
        realStore.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest("S-20260912_100000", 1000L, "world"));
        realStore.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest("S-20260912_110000", 2000L, "world"));
        realStore.saveSnapshot(new io.github.truscdo.foldbackup.core.store.SnapshotManifest("S-20260912_120000", 3000L, "world"));
        realStore.setPinned(2000L, true);

        var provider = SnapshotIdSuggestions.suggestForDelete(() -> realStore);

        // Initial empty input: suggests --dry-run and only unpinned snapshots
        var initial = provider.getSuggestions(null, new SuggestionsBuilder("/foldbackup delete ", 19)).get();
        List<String> initialTexts = initial.getList().stream().map(Suggestion::getText).toList();
        assertTrue(initialTexts.contains("--dry-run"), initialTexts.toString());
        assertTrue(initialTexts.contains("S-20260912_120000"), initialTexts.toString());
        assertTrue(initialTexts.contains("S-20260912_100000"), initialTexts.toString());
        assertFalse(initialTexts.contains("S-20260912_110000"), "pinned snapshot must be excluded");

        // After "--dry-run ": offset advances past the space and --dry-run is no longer suggested
        String afterFlagInput = "/foldbackup delete --dry-run ";
        var afterFlag = provider.getSuggestions(null, new SuggestionsBuilder(afterFlagInput, 19)).get();
        assertEquals(afterFlagInput.length(), afterFlag.getRange().getStart());
        List<String> afterFlagTexts = afterFlag.getList().stream().map(Suggestion::getText).toList();
        assertFalse(afterFlagTexts.contains("--dry-run"));
        assertEquals(List.of("S-20260912_100000", "S-20260912_120000"), afterFlagTexts);

        // After entering one ID + space: already entered ID is excluded, --dry-run still offered
        String afterIdInput = "/foldbackup delete S-20260912_120000 ";
        var afterId = provider.getSuggestions(null, new SuggestionsBuilder(afterIdInput, 19)).get();
        assertEquals(afterIdInput.length(), afterId.getRange().getStart());
        List<String> afterIdTexts = afterId.getList().stream().map(Suggestion::getText).toList();
        assertTrue(afterIdTexts.contains("--dry-run"));
        assertTrue(afterIdTexts.contains("S-20260912_100000"));
        assertFalse(afterIdTexts.contains("S-20260912_120000"));
        assertFalse(afterIdTexts.contains("S-20260912_110000"));
    }

    private SnapshotStore store(List<String> ids) throws Exception {
        return new SnapshotStore(root) {
            @Override public List<String> listSnapshots() { return ids; }
        };
    }
}
