package io.github.truscdo.foldbackup.core.rollback;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PreviewDifferenceTest {
    @Test void distinguishesBlockPlacementRemovalAndReplacement() {
        assertEquals(new PreviewDifference.Counts(1, 1, 1), PreviewDifference.blocks(
                new String[]{"air", "stone", "gold", "stone"},
                new String[]{"gold", "air", "stone", "stone"}, "air"::equals));
    }
    @Test void refusesDifferentWorldHeights() {
        assertThrows(IllegalArgumentException.class, () -> PreviewDifference.blocks(new String[]{"air"}, new String[0], "air"::equals));
    }
    @Test void distinguishesAddedRemovedAndChangedData() {
        assertEquals(new PreviewDifference.Counts(1, 1, 1),
                PreviewDifference.counts(Map.of("same", "a", "changed", "a", "removed", "a"),
                        Map.of("same", "a", "changed", "b", "added", "a")));
    }
    @Test void sameIdentityAndValueIsUnchanged() {
        assertEquals(new PreviewDifference.Counts(0, 0, 0),
                PreviewDifference.counts(Map.of("id", Map.of("inventory", 7)), Map.of("id", Map.of("inventory", 7))));
    }
    @Test void emptyHistoricalDataRemovesEveryCurrentIdentity() {
        assertEquals(new PreviewDifference.Counts(0, 2, 0), PreviewDifference.counts(Map.of("a", 1, "b", 2), Map.of()));
    }
}
