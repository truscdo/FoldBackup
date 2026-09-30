package io.github.truscdo.foldbackup.it.scenario;

import io.github.truscdo.foldbackup.it.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real entity-region snapshots, server entity registration and tracking across all vanilla dimensions. */
public class EntityRollbackIT extends IntegrationTestBase {
    private String command(String dimension, String command) throws Exception {
        return rcon.sendCommand("/execute in " + dimension + " run " + command);
    }
    private void exists(String dim, String tag, boolean expected) throws Exception {
        String result = command(dim, "data get entity @e[distance=0..,tag=" + tag + ",limit=1] Tags");
        assertEquals(expected, result.contains("\"" + tag + "\""), tag + ": " + result);
    }
    private String rollback(String dim, int x, int z, String snapshot) throws Exception {
        return rcon.sendCommand("/foldbackup rollback chunk in " + dim + " " + x + " " + z + " " + snapshot);
    }
    private void load(String dim, int x) throws Exception {
        command(dim, "forceload add " + x + " 0 " + (x + 16) + " 0");
        Thread.sleep(1000);
        command(dim, "kill @e[distance=0..,type=!minecraft:player,x=" + x + ",y=-64,z=0,dx=31,dy=512,dz=15]");
        Thread.sleep(1100); // Living entities can remain in the death animation for 20 ticks.
    }

    @Test void restoresIdentityNbtPassengersAndLeavesNeighborsInEveryDimension() throws Exception {
        rcon.sendCommand("/gamerule doMobSpawning false");
        for (String dim : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            load(dim, 160);
            command(dim, "summon minecraft:pig 168 100 8 {Tags:[\"rollback_root\"],NoAI:1b,NoGravity:1b,Rotation:[45f,10f],CustomName:'{\"text\":\"before\"}',Passengers:[{id:\"minecraft:armor_stand\",Tags:[\"rollback_passenger\"],NoGravity:1b,Invisible:1b}]}");
            command(dim, "summon minecraft:marker 184 100 8 {Tags:[\"rollback_neighbor\"]}");
            Thread.sleep(300);
            String rootId = command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] UUID");
            String passengerId = command(dim, "data get entity @e[distance=0..,tag=rollback_passenger,limit=1] UUID");
            String before = command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] Pos");
            String rotation = command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] Rotation");
            String snapshot = doBackup();
            command(dim, "kill @e[distance=0..,tag=rollback_passenger]");
            command(dim, "kill @e[distance=0..,tag=rollback_root]");
            command(dim, "summon minecraft:marker 168 100 8 {Tags:[\"rollback_new\"]}");
            String response = rollback(dim, 10, 0, snapshot);
            assertTrue(response.contains("restored"), response);
            exists(dim, "rollback_root", true); exists(dim, "rollback_passenger", true);
            exists(dim, "rollback_new", false); exists(dim, "rollback_neighbor", true);
            assertEquals(rootId, command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] UUID"));
            assertEquals(passengerId, command(dim, "data get entity @e[distance=0..,tag=rollback_passenger,limit=1] UUID"));
            assertEquals(before, command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] Pos"));
            assertEquals(rotation, command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] Rotation"));
            assertTrue(command(dim, "data get entity @e[distance=0..,tag=rollback_root,limit=1] Passengers").contains("rollback_passenger"));
            // A second replacement exercises removal/re-registration of the same UUIDs.
            assertTrue(rollback(dim, 10, 0, snapshot).contains("restored"));
        }
    }

    @Test void emptySnapshotClearsEntitiesAndUuidConflictStillRestoresBlocks() throws Exception {
        String dim = "minecraft:overworld";
        load(dim, 192);
        command(dim, "setblock 200 100 8 minecraft:gold_block");
        String empty = doBackup();
        command(dim, "summon minecraft:armor_stand 200 101 8 {Tags:[\"rollback_conflict\"],NoGravity:1b}");
        assertTrue(rollback(dim, 12, 0, empty).contains("restored"));
        exists(dim, "rollback_conflict", false);
        command(dim, "summon minecraft:armor_stand 200 101 8 {Tags:[\"rollback_conflict\"],NoGravity:1b}");
        String populated = doBackup();
        command(dim, "tp @e[distance=0..,tag=rollback_conflict] 216 101 8");
        command(dim, "setblock 200 100 8 minecraft:diamond_block");
        String response = rollback(dim, 12, 0, populated);
        assertTrue(response.contains("skipped: 1"), response);
        assertTrue(response.contains("restored"), response);
        String block = command(dim, "execute if block 200 100 8 minecraft:gold_block");
        assertTrue(block.toLowerCase().contains("passed"), block);
        exists(dim, "rollback_conflict", true);
    }

    @Test void crossDimensionPassengerConflictPreservesCurrentTreeAndRestoresOtherEntities() throws Exception {
        String dim = "minecraft:overworld", other = "minecraft:the_nether";
        load(dim, 288); load(other, 288);
        command(dim, "summon minecraft:pig 296 100 8 {Tags:[\"protected_root\"],NoAI:1b,NoGravity:1b,Passengers:[{id:\"minecraft:armor_stand\",Tags:[\"conflicting_passenger\"],NoGravity:1b}]}");
        command(dim, "summon minecraft:marker 297 100 8 {Tags:[\"independent_restore\"]}");
        command(dim, "setblock 296 99 8 minecraft:gold_block");
        String snapshot = doBackup();
        command(dim, "ride @e[distance=0..,tag=conflicting_passenger,limit=1] dismount");
        command(dim, "execute as @e[distance=0..,tag=conflicting_passenger] in " + other + " run tp @s 296 100 8");
        command(dim, "data merge entity @e[distance=0..,tag=protected_root,limit=1] {CustomName:'{\"text\":\"preserved\"}'}");
        command(dim, "kill @e[distance=0..,tag=independent_restore]");
        command(dim, "setblock 296 99 8 minecraft:diamond_block");
        String response = rollback(dim, 18, 0, snapshot);
        assertTrue(response.contains("restored: 1") && response.contains("skipped: 1")
                && response.contains("preserved: 1"), response);
        exists(dim, "independent_restore", true);
        exists(other, "conflicting_passenger", true);
        exists(dim, "conflicting_passenger", false);
        assertTrue(command(dim, "data get entity @e[distance=0..,tag=protected_root,limit=1] CustomName").contains("preserved"));
        assertTrue(command(dim, "execute if block 296 99 8 minecraft:gold_block").toLowerCase().contains("passed"));
    }

    @Test void cliRestoresSameIdentityAfterServerRestartAndRefusesRunningWorld() throws Exception {
        String dim = "minecraft:overworld";
        load(dim, 224);
        command(dim, "summon minecraft:armor_stand 232 100 8 {Tags:[\"rollback_cli\"],NoGravity:1b,CustomName:'{\"text\":\"offline\"}'}");
        String id = command(dim, "data get entity @e[distance=0..,tag=rollback_cli,limit=1] UUID");
        String snapshot = doBackup();
        command(dim, "kill @e[distance=0..,tag=rollback_cli]");
        command(dim, "summon minecraft:marker 232 100 8 {Tags:[\"rollback_cli_new\"]}");
        assertTrue(rollback(dim, 14, 0, snapshot).contains("restored"));
        assertEquals(id, command(dim, "data get entity @e[distance=0..,tag=rollback_cli,limit=1] UUID"));
        command(dim, "kill @e[distance=0..,tag=rollback_cli]");
        command(dim, "summon minecraft:marker 232 100 8 {Tags:[\"rollback_cli_new\"]}");
        Path root = runDir.resolve("foldbackups");
        assertEquals(0, cli("extract", "--output", root.resolve(".foldbackup-cli").toString()));
        String[] args = {"snapshot", "rollback", snapshot, "--world", worldDir.toString(),
                "--chunk", "14", "0", "--yes", "--snapshot-root", root.toString(),
                "--lib", root.resolve(".foldbackup-cli").toString()};
        assertNotEquals(0, cli(args), "CLI must refuse a running world");
        rcon.close(); serverManager.close();
        assertEquals(0, cli(args), Files.readString(runDir.resolve("entity-cli-output.log")));
        setupIntegrationTest();
        command(dim, "forceload add 224 0");
        Thread.sleep(1000);
        exists(dim, "rollback_cli", true); exists(dim, "rollback_cli_new", false);
        assertEquals(id, command(dim, "data get entity @e[distance=0..,tag=rollback_cli,limit=1] UUID"));
    }

    private int cli(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("foldbackup.fatJar")));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(runDir.resolve("entity-cli-output.log").toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("CLI timed out"); }
        return process.exitValue();
    }

    @Test void unknownTypeAndInvalidNbtPreserveEntitiesAndRestoreBlocks() throws Exception {
        String dim = "minecraft:overworld";
        load(dim, 256);
        command(dim, "summon minecraft:marker 264 100 8 {Tags:[\"rollback_invalid\"]}");
        command(dim, "setblock 264 99 8 minecraft:gold_block");
        String original = doBackup();
        command(dim, "setblock 264 99 8 minecraft:diamond_block");
        String uuid = command(dim, "data get entity @e[distance=0..,tag=rollback_invalid,limit=1] UUID");
        // The running mod owns an in-memory snapshot index. Install deliberately invalid
        // test snapshots while stopped, then reload the index by restarting the server.
        rcon.close(); serverManager.close();
        List<String> invalidSnapshots = List.of(invalidSnapshot(original, "type"), invalidSnapshot(original, "uuid"));
        setupIntegrationTest();
        Thread.sleep(1000);
        for (String invalid : invalidSnapshots) {
            command(dim, "setblock 264 99 8 minecraft:diamond_block");
            String response = rollback(dim, 16, 0, invalid);
            assertTrue(response.contains("restored"), response);
            boolean partialPreservation = response.contains("skipped: 1") && response.contains("preserved: 1");
            boolean fullPreservation = response.contains("historical entities could not be applied")
                    && response.contains("current entities were preserved");
            assertTrue(partialPreservation || fullPreservation, response);
            assertEquals(uuid, command(dim, "data get entity @e[distance=0..,tag=rollback_invalid,limit=1] UUID"));
            String block = command(dim, "execute if block 264 99 8 minecraft:gold_block");
            assertTrue(block.toLowerCase().contains("passed"), block);
        }
    }

    private String invalidSnapshot(String original, String variant) throws Exception {
        var store = new io.github.truscdo.foldbackup.core.store.SnapshotStore(runDir.resolve("foldbackups"));
        var blobs = new io.github.truscdo.foldbackup.core.store.BlobStore(runDir.resolve("foldbackups"), 6);
        long sourceEpoch = store.resolveEpoch(original);
        var source = store.loadSnapshot(sourceEpoch);
        var resolved = io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport.resolveEntities(source, blobs, 16, 0, "");
        byte[] raw = resolved.snapshot().nbt();
        byte[] from = (variant.equals("type") ? "minecraft:marker" : "UUID").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] to = (variant.equals("type") ? "missingxx:marker" : "BAD!").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(from.length, to.length);
        boolean replaced = false;
        for (int i = 0; i <= raw.length - from.length; i++) {
            if (Arrays.equals(raw, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, raw, i, to.length); replaced = true;
            }
        }
        assertTrue(replaced);
        var region = new io.github.truscdo.foldbackup.core.format.Region(0, 0, 0);
        region.setChunk(16, new io.github.truscdo.foldbackup.core.format.ChunkData(raw, 16, 0));
        Path lrhTemp = Files.createTempFile(runDir, "invalid-entities-", ".lrh");
        byte[] lrh;
        try {
            try (FileChannel channel = FileChannel.open(lrhTemp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                io.github.truscdo.foldbackup.core.format.LrhFormat.write(channel,
                        io.github.truscdo.foldbackup.core.format.RegionStream.of(region), 6);
            }
            lrh = Files.readAllBytes(lrhTemp);
        } finally {
            Files.deleteIfExists(lrhTemp);
        }
        String hash = io.github.truscdo.foldbackup.core.HashUtil.xxHash128Hex(lrh);
        String path = blobs.storeLrh(hash, lrh);
        var entries = new ArrayList<>(source.getRegions());
        entries.removeIf(e -> e.getFilename().equals("entities/r.0.0.mca"));
        entries.add(new io.github.truscdo.foldbackup.core.store.RegionEntry("entities/r.0.0.mca", hash, path, lrh.length, raw.length));
        long epochSeconds = sourceEpoch - 86400;
        String id = io.github.truscdo.foldbackup.core.store.SnapshotStore.generateSnapshotId(epochSeconds);
        while (store.loadSnapshot(epochSeconds) != null || store.listSnapshots().contains(id)) {
            epochSeconds--;
            id = io.github.truscdo.foldbackup.core.store.SnapshotStore.generateSnapshotId(epochSeconds);
        }
        var snapshot = new io.github.truscdo.foldbackup.core.store.SnapshotManifest(id, epochSeconds, "world");
        snapshot.setRegions(entries); snapshot.setExtraFiles(source.getExtraFiles());
        store.saveSnapshot(snapshot);
        return id;
    }
}
