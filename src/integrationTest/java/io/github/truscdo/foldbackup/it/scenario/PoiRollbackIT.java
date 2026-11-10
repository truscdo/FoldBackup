package io.github.truscdo.foldbackup.it.scenario;

import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.it.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Online chunk rollback rebuilds the target chunk's POI column from the restored
 * blocks and recomputes ticket occupancy from loaded villagers.
 */
public class PoiRollbackIT extends IntegrationTestBase {

    private static final int BELL_X = 8, BELL_Y = 64, BELL_Z = 8;
    private static final int TABLE_X = 24, TABLE_Y = 64, TABLE_Z = 8;

    @Test
    void rollbackRestoresPoiColumnAndChargesVillagerTicket() throws Exception {
        rcon.sendCommand("/gamerule doMobSpawning false");
        // Chunks [0,0] and [1,0]: target chunk and its untouched neighbor.
        rcon.sendCommand("/forceload add 0 0 31 15");
        Thread.sleep(1000);

        rcon.sendCommand("/setblock " + BELL_X + " " + BELL_Y + " " + BELL_Z + " minecraft:bell");
        rcon.sendCommand("/setblock " + TABLE_X + " " + TABLE_Y + " " + TABLE_Z + " minecraft:cartography_table");
        // A loaded villager whose meeting-point memory names the bell: after rollback the
        // rebuilt meeting record must have one ticket consumed.
        rcon.sendCommand("/summon minecraft:villager 8 64 9 "
                + "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,"
                + "Brain:{memories:{\"minecraft:meeting_point\":"
                + "{value:{dimension:\"minecraft:overworld\",pos:[I;" + BELL_X + "," + BELL_Y + "," + BELL_Z + "]}}}}}");
        Thread.sleep(1000);

        String snapshot = doBackup();

        // Remove the bell so the POI record disappears before the rollback.
        rcon.sendCommand("/setblock " + BELL_X + " " + BELL_Y + " " + BELL_Z + " minecraft:air");
        Thread.sleep(500);

        String response = rcon.sendCommand(
                "/foldbackup rollback chunk in minecraft:overworld 0 0 " + snapshot);
        System.out.println("  Rollback response: " + response);
        assertTrue(response.contains("restored"), response);

        rcon.sendCommand("/save-all flush");
        Thread.sleep(1500);

        Path poiFile = worldDir.resolve("poi/r.0.0.mca");
        assertTrue(Files.exists(poiFile), "POI region file should exist: " + poiFile);
        List<PoiRecord> records = readPoiRecords(poiFile, 0, 0);
        System.out.println("  Target POI records: " + records);

        PoiRecord bell = records.stream()
                .filter(record -> record.x == BELL_X && record.y == BELL_Y && record.z == BELL_Z)
                .findFirst().orElseThrow(() -> new AssertionError("Missing bell POI: " + records));
        assertEquals("minecraft:meeting", bell.type);
        assertEquals(31, bell.freeTickets,
                "One loaded villager memory should consume one of the bell's 32 tickets");

        List<PoiRecord> neighbor = readPoiRecords(poiFile, 1, 0);
        System.out.println("  Neighbor POI records: " + neighbor);
        PoiRecord table = neighbor.stream()
                .filter(record -> record.x == TABLE_X && record.y == TABLE_Y && record.z == TABLE_Z)
                .findFirst().orElseThrow(() -> new AssertionError("Neighbor-chunk POI must not be removed: " + neighbor));
        assertEquals("minecraft:cartographer", table.type);
        assertEquals(1, table.freeTickets);
    }

    @Test
    void rollbackClearsStalePoiRecordAndRebuildsJobSitePoi() throws Exception {
        rcon.sendCommand("/gamerule doMobSpawning false");
        loadChunk(4, 0);
        rcon.sendCommand("/setblock 72 64 8 minecraft:stone");
        String snapshot = doBackup();

        // Sections are swapped without going through setBlockState, so the record of a
        // POI placed after the backup can only disappear through the rebuild's removal.
        rcon.sendCommand("/setblock 72 64 8 minecraft:lectern");
        Thread.sleep(500);
        rollback(4, 0, snapshot);
        saveAndWait();
        assertBlock(72, 64, 8, "minecraft:stone");
        assertNoPoi(4, 0, 72, 64, 8);

        // The same position now rebuilds a job-site POI whose block owns a block entity.
        rcon.sendCommand("/setblock 72 64 8 minecraft:lectern");
        Thread.sleep(500);
        String secondSnapshot = doBackup();
        assertPoi(4, 0, 72, 64, 8, "minecraft:librarian", 1);
        rollback(4, 0, secondSnapshot);
        saveAndWait();
        assertPoi(4, 0, 72, 64, 8, "minecraft:librarian", 1);
    }

    @Test
    void rollbackCountsTicketsFromPostRollbackVillagersOnly() throws Exception {
        rcon.sendCommand("/gamerule doMobSpawning false");
        loadChunk(8, 0);
        rcon.sendCommand("/setblock 136 64 8 minecraft:bell");
        rcon.sendCommand("/setblock 140 64 8 minecraft:bell");
        String snapshot = doBackup();
        assertPoi(8, 0, 136, 64, 8, "minecraft:meeting", 32);
        assertPoi(8, 0, 140, 64, 8, "minecraft:meeting", 32);

        // No loaded villager holds the bells yet, so both rebuilt records stay full.
        rollback(8, 0, snapshot);
        saveAndWait();
        assertPoi(8, 0, 136, 64, 8, "minecraft:meeting", 32);
        assertPoi(8, 0, 140, 64, 8, "minecraft:meeting", 32);

        // The villager in the snapshot remembers the bell at 140; after the backup its
        // live memory is moved to the bell at 136. Tickets must be counted from the
        // restored villager, so the bell at 140 loses one ticket and the bell at 136
        // stays full. Counting before the entity rollback would invert the result.
        rcon.sendCommand("/summon minecraft:villager 136 64 9 "
                + "{Tags:[\"poi_order_holder\"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,"
                + "PersistenceRequired:1b,Brain:{memories:{\"minecraft:meeting_point\":"
                + "{value:{dimension:\"minecraft:overworld\",pos:[I;140,64,8]}}}}}");
        assertTrue(awaitMemory("poi_order_holder", "meeting_point"),
                "The villager's meeting memory should be applied");
        String secondSnapshot = doBackup();
        rcon.sendCommand("/data merge entity @e[tag=poi_order_holder,limit=1] "
                + "{Brain:{memories:{\"minecraft:meeting_point\":"
                + "{value:{dimension:\"minecraft:overworld\",pos:[I;136,64,8]}}}}}");
        String liveMemories = memories("poi_order_holder");
        assertTrue(liveMemories.contains("136"), "The live memory should name the bell at 136: " + liveMemories);
        assertFalse(liveMemories.contains("140"), "The snapshot memory should be replaced: " + liveMemories);

        rollback(8, 0, secondSnapshot);
        saveAndWait();
        assertTrue(entityExists("poi_order_holder"), "The villager is restored from the snapshot");
        assertPoi(8, 0, 136, 64, 8, "minecraft:meeting", 32);
        assertPoi(8, 0, 140, 64, 8, "minecraft:meeting", 31);
    }

    @Test
    void rollbackReleasesNeighborPoiTicketOfReplacedVillager() throws Exception {
        rcon.sendCommand("/gamerule doMobSpawning false");
        loadChunk(12, 0);
        loadChunk(13, 0);
        // A sealed room keeps the villager in place and gives it a path to the bell.
        rcon.sendCommand("/fill 198 63 4 218 63 12 minecraft:stone");
        rcon.sendCommand("/fill 198 64 4 218 67 12 minecraft:air");
        rcon.sendCommand("/fill 198 64 4 218 67 4 minecraft:stone");
        rcon.sendCommand("/fill 198 64 12 218 67 12 minecraft:stone");
        rcon.sendCommand("/fill 198 64 4 198 67 12 minecraft:stone");
        rcon.sendCommand("/fill 218 64 4 218 67 12 minecraft:stone");
        rcon.sendCommand("/fill 198 67 4 218 67 12 minecraft:stone");
        rcon.sendCommand("/setblock 216 64 8 minecraft:bell");
        rcon.sendCommand("/summon minecraft:villager 202 64 8 "
                + "{Tags:[\"poi_neighbor_holder\"],Silent:1b,Invulnerable:1b,PersistenceRequired:1b}");
        assertTrue(awaitMemory("poi_neighbor_holder", "meeting_point"),
                "The villager should take the bell's ticket before the backup");
        String snapshot = doBackup();
        // Premise: the ticket is really taken, otherwise the release below is unobservable.
        assertPoi(13, 0, 216, 64, 8, "minecraft:meeting", 31);

        rollback(12, 0, snapshot);
        saveAndWait();
        assertTrue(entityExists("poi_neighbor_holder"), "The villager is restored from the snapshot");
        // Discarding the old villager releases its claim on the neighbour chunk's bell.
        // The rebuild counts holders of the target chunk only, so the bell stays full.
        assertPoi(13, 0, 216, 64, 8, "minecraft:meeting", 32);
    }

    private void loadChunk(int chunkX, int chunkZ) throws Exception {
        int x = chunkX << 4, z = chunkZ << 4;
        rcon.sendCommand("/forceload add " + x + " " + z + " " + (x + 15) + " " + (z + 15));
        Thread.sleep(1000);
    }

    private void rollback(int chunkX, int chunkZ, String snapshot) throws Exception {
        String response = rcon.sendCommand(
                "/foldbackup rollback chunk in minecraft:overworld " + chunkX + " " + chunkZ + " " + snapshot);
        assertTrue(response.contains("restored"), response);
    }

    private void saveAndWait() throws Exception {
        rcon.sendCommand("/save-all flush");
        Thread.sleep(1500);
    }

    private void assertBlock(int x, int y, int z, String block) throws IOException {
        String result = rcon.sendCommand("/execute if block " + x + " " + y + " " + z + " " + block);
        assertTrue(result.toLowerCase().contains("passed"),
                "Expected " + block + " at " + x + "," + y + "," + z + ": " + result);
    }

    private void assertPoi(int chunkX, int chunkZ, int x, int y, int z, String type, int freeTickets)
            throws IOException {
        PoiRecord record = findPoi(chunkX, chunkZ, x, y, z);
        assertNotNull(record, "Missing POI at " + x + "," + y + "," + z
                + " in chunk [" + chunkX + "," + chunkZ + "]");
        assertEquals(type, record.type);
        assertEquals(freeTickets, record.freeTickets,
                "Unexpected free tickets for " + type + " at " + x + "," + y + "," + z);
    }

    private void assertNoPoi(int chunkX, int chunkZ, int x, int y, int z) throws IOException {
        assertNull(findPoi(chunkX, chunkZ, x, y, z),
                "Stale POI must be removed at " + x + "," + y + "," + z);
    }

    private PoiRecord findPoi(int chunkX, int chunkZ, int x, int y, int z) throws IOException {
        Path poiFile = worldDir.resolve("poi/r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        assertTrue(Files.exists(poiFile), "POI region file should exist: " + poiFile);
        for (PoiRecord record : readPoiRecords(poiFile, chunkX, chunkZ)) {
            if (record.x == x && record.y == y && record.z == z) return record;
        }
        return null;
    }

    private boolean entityExists(String tag) throws IOException {
        return rcon.sendCommand("/data get entity @e[tag=" + tag + ",limit=1] Tags").contains(tag);
    }

    private String memories(String tag) throws IOException {
        return rcon.sendCommand("/data get entity @e[tag=" + tag + ",limit=1] Brain.memories");
    }

    private boolean awaitMemory(String tag, String memory) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (memories(tag).contains(memory)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private record PoiRecord(int x, int y, int z, String type, int freeTickets) {
    }

    private static List<PoiRecord> readPoiRecords(Path regionFile, int chunkX, int chunkZ) throws IOException {
        byte[] nbt = readChunk(regionFile, chunkX, chunkZ);
        if (nbt == null) return List.of();
        Map<String, Object> root = readNbt(nbt);
        if (!(root.get("Sections") instanceof Map<?, ?> sections)) return List.of();
        List<PoiRecord> result = new ArrayList<>();
        for (Object sectionValue : sections.values()) {
            if (!(sectionValue instanceof Map<?, ?> section)) continue;
            if (!(section.get("Records") instanceof List<?> records)) continue;
            for (Object recordValue : records) {
                if (!(recordValue instanceof Map<?, ?> record)) continue;
                int[] pos = (int[]) record.get("pos");
                result.add(new PoiRecord(pos[0], pos[1], pos[2], (String) record.get("type"),
                        ((Number) record.get("free_tickets")).intValue()));
            }
        }
        return result;
    }

    private static byte[] readChunk(Path regionFile, int chunkX, int chunkZ) throws IOException {
        int target = (chunkZ & 31) * 32 + (chunkX & 31);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] current = {-1};
        McaStream.read(regionFile, new RegionStream.Consumer() {
            @Override public void begin(int index, int timestamp) { current[0] = index; }
            @Override public void data(byte[] buffer, int offset, int length) {
                if (current[0] == target) data.write(buffer, offset, length);
            }
            @Override public void end() { }
        });
        return data.size() == 0 ? null : data.toByteArray();
    }

    private static Map<String, Object> readNbt(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != 10) throw new IOException("POI chunk must be a compound");
            in.readUTF();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readTag(in, 10);
            return root;
        }
    }

    private static Object readTag(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] array = new byte[in.readInt()];
                in.readFully(array);
                yield array;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int count = in.readInt();
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) list.add(readTag(in, elementType));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                int childType;
                while ((childType = in.readUnsignedByte()) != 0) {
                    map.put(in.readUTF(), readTag(in, childType));
                }
                yield map;
            }
            case 11 -> {
                int[] array = new int[in.readInt()];
                for (int i = 0; i < array.length; i++) array[i] = in.readInt();
                yield array;
            }
            case 12 -> {
                long[] array = new long[in.readInt()];
                for (int i = 0; i < array.length; i++) array[i] = in.readLong();
                yield array;
            }
            default -> throw new IOException("Unknown NBT type " + type);
        };
    }
}
