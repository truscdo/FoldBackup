package com.foldbackup.core.format;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.foldbackup.core.format.EntityNbtFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityRecoveryTest {
    @Test void skipsEveryDuplicateTreeAndKeepsIndependentEntities() throws Exception {
        UUID duplicate = UUID.randomUUID(), valid = UUID.randomUUID(), root = UUID.randomUUID();
        var result = EntityRegionCodec.recover(chunk(0, 0,
                entity(duplicate, 8, 8),
                entity(root, 8, 8, "minecraft:pig", 11, "", entity(duplicate, 8, 8)),
                entity(valid, 8, 8)), 0, 0);
        assertEquals(2, result.skippedTrees());
        assertEquals(Set.of(duplicate, root), result.skippedIds());
        assertEquals(Set.of(valid), EntityRegionCodec.uuids(EntityRegionCodec.read(result.snapshot().nbt(), 0, 0)));
        assertFalse(result.preserveAll());
    }

    @Test void invalidTreeWithKnownIdentityDoesNotDiscardValidNeighbor() throws Exception {
        UUID bad = UUID.randomUUID(), valid = UUID.randomUUID();
        var result = EntityRegionCodec.recover(chunk(0, 0,
                entity(bad, 8, 8, "minecraft:pig", 11, "Pos", null), entity(valid, 8, 8)), 0, 0);
        assertEquals(Set.of(bad), result.skippedIds());
        assertEquals(Set.of(valid), EntityRegionCodec.uuids(result.snapshot()));
        assertFalse(result.preserveAll());
    }

    @Test void missingIdentityRequestsPreservationAndPlayerPassengerSkipsWholeTree() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(EntityRegionCodec.recover(chunk(0, 0,
                entity(id, 8, 8, "minecraft:pig", 11, "UUID", null)), 0, 0).preserveAll());
        var result = EntityRegionCodec.recover(chunk(0, 0, entity(id, 8, 8, "minecraft:pig", 11, "",
                entity(UUID.randomUUID(), 8, 8, "minecraft:player", 11, "", null))), 0, 0);
        assertEquals(1, result.skippedTrees());
        assertTrue(result.snapshot().isEmpty());
    }
}
