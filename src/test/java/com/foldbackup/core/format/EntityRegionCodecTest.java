package com.foldbackup.core.format;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.foldbackup.core.format.EntityNbtFixture.*;

class EntityRegionCodecTest {
    @Test void readsRealNeoForgeFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/entities/neoforge-1.21.1-chunk-10-0.nbt")) {
            assertNotNull(in);
            byte[] raw = in.readAllBytes();
            var snapshot = EntityRegionCodec.read(raw, 10, 0);
            assertEquals(3955, snapshot.dataVersion());
            var pig = snapshot.entities().stream().filter(e -> e.id().equals("minecraft:pig")).findFirst().orElseThrow();
            assertEquals("minecraft:armor_stand", pig.passengers().getFirst().id());
            assertArrayEquals(raw, snapshot.nbt());
        }
    }
    @Test void roundTripPreservesUnknownNbtAndDefendsArrays() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] raw = chunk(-1, 0, entity(id, -8, 8));
        var snapshot = EntityRegionCodec.read(raw, -1, 0);
        assertArrayEquals(raw, snapshot.nbt());
        assertEquals(id, snapshot.entities().getFirst().uuid());
        assertEquals(45, snapshot.entities().getFirst().yaw());
        snapshot.nbt()[0] = 0;
        snapshot.entities().getFirst().nbt()[0] = 0;
        assertArrayEquals(raw, snapshot.nbt());
        assertEquals(10, snapshot.entities().getFirst().nbt()[0]);
    }
    @Test void missingAndEmptyListsAreEmpty() throws Exception {
        assertTrue(EntityRegionCodec.read(chunk(0, 0, (byte[][]) null), 0, 0).isEmpty());
        assertTrue(EntityRegionCodec.read(chunk(0, 0), 0, 0).isEmpty());
        assertTrue(EntityRegionCodec.read(EntityRegionCodec.empty(0, 0).nbt(), 0, 0).isEmpty());
    }
    @Test void uuidRepresentationsNormalizeAndPassengersAreRetained() throws Exception {
        for (int type : new int[]{4, 8, 11}) {
            UUID id = UUID.randomUUID(), passenger = UUID.randomUUID();
            var snapshot = EntityRegionCodec.read(chunk(0, 0,
                    entity(id, 8, 8, "minecraft:pig", type, "", entity(passenger, 8, 8))), 0, 0);
            var again = EntityRegionCodec.read(snapshot.nbt(), 0, 0);
            assertEquals(id, again.entities().getFirst().uuid());
            assertEquals(passenger, again.entities().getFirst().passengers().getFirst().uuid());
        }
    }
    @Test void invalidFieldsAndDuplicateNestedUuidFail() throws Exception {
        UUID id = UUID.randomUUID();
        for (String field : new String[]{"id", "UUID", "Pos", "Rotation"}) {
            byte[] raw = chunk(0, 0, entity(id, 8, 8, "minecraft:pig", 11, field, null));
            assertTrue(assertThrows(IOException.class, () -> EntityRegionCodec.read(raw, 0, 0)).getMessage().contains(field));
        }
        byte[] duplicates = chunk(0, 0, entity(id, 8, 8, "minecraft:pig", 11, "", entity(id, 8, 8)));
        assertThrows(IOException.class, () -> EntityRegionCodec.read(duplicates, 0, 0));
        byte[] nan = chunk(0, 0, entity(id, Double.NaN, 8));
        assertThrows(IOException.class, () -> EntityRegionCodec.read(nan, 0, 0));
        byte[] outside = chunk(0, 0, entity(id, 16, 8));
        assertThrows(IOException.class, () -> EntityRegionCodec.read(outside, 0, 0));
    }
    @Test void truncationWrongPositionAndPlayerExclusion() throws Exception {
        byte[] raw = chunk(0, 0, entity(UUID.randomUUID(), 8, 8));
        for (int n = 0; n < raw.length; n++) {
            byte[] truncated = Arrays.copyOf(raw, n);
            assertThrows(IOException.class, () -> EntityRegionCodec.read(truncated, 0, 0));
        }
        assertThrows(IOException.class, () -> EntityRegionCodec.read(raw, 1, 0));
        var players = EntityRegionCodec.read(chunk(0, 0, entity(UUID.randomUUID(), 8, 8, "minecraft:player", 11, "", null)), 0, 0);
        assertEquals(1, players.skippedPlayers());
        assertTrue(EntityRegionCodec.read(players.nbt(), 0, 0).isEmpty());
    }
}
