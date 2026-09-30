package com.foldbackup.core.format;

import com.foldbackup.core.format.NbtCodec.Tag;
import com.foldbackup.core.format.NbtCodec.TagList;
import java.io.*;
import java.util.*;

import static com.foldbackup.core.format.NbtCodec.compound;
import static com.foldbackup.core.format.NbtCodec.compounds;
import static com.foldbackup.core.format.NbtCodec.parse;
import static com.foldbackup.core.format.NbtCodec.write;

/** Game-independent entity-region NBT codec. Unknown tags survive round trips. */
public final class EntityRegionCodec {
    private EntityRegionCodec() {}

    public record EntityTag(String id, UUID uuid, double x, double y, double z,
                            float yaw, float pitch, byte[] nbt, List<EntityTag> passengers) {
        public EntityTag { nbt = nbt.clone(); passengers = List.copyOf(passengers); }
        @Override public byte[] nbt() { return nbt.clone(); }
    }

    public record EntityChunkSnapshot(byte[] nbt, List<EntityTag> entities, int skippedPlayers, int dataVersion) {
        public EntityChunkSnapshot { nbt = nbt.clone(); entities = List.copyOf(entities); }
        @Override public byte[] nbt() { return nbt.clone(); }
        public boolean isEmpty() { return entities.isEmpty(); }
    }

    public static EntityChunkSnapshot read(byte[] rawChunk, int chunkX, int chunkZ) throws IOException {
        try { return decode(rawChunk, chunkX, chunkZ); }
        catch (EOFException e) { throw new IOException("Truncated entity NBT for chunk [" + chunkX + ", " + chunkZ + "]", e); }
    }

    /** Rollback-only recovery; strict readers used for conflict scans remain unchanged. */
    public record Recovery(EntityChunkSnapshot snapshot, Set<UUID> skippedIds, List<String> issues,
                           int skippedTrees, boolean preserveAll) {}

    public static Recovery recover(byte[] raw, int x, int z) throws IOException {
        Tag root = parse(raw);
        Map<String, Tag> fields = compound(root);
        Tag position = fields.get("Position");
        if (position != null && (position.type() != 11 || !Arrays.equals((int[]) position.value(), new int[]{x, z})))
            throw new IOException("Entity chunk Position differs from target");
        Tag version = fields.get("DataVersion");
        if (version != null && version.type() != 3) throw new IOException("Invalid entity DataVersion");
        List<Tag> roots = compounds(fields.get("Entities"), "Entities");
        List<Set<UUID>> identities = new ArrayList<>();
        Map<UUID, Integer> counts = new HashMap<>();
        boolean[] unknown = {false};
        for (Tag tree : roots) {
            Set<UUID> ids = new HashSet<>();
            gather(tree, ids, counts, unknown);
            identities.add(ids);
        }
        List<EntityTag> kept = new ArrayList<>();
        Set<UUID> skipped = new HashSet<>();
        List<String> issues = new ArrayList<>();
        int skippedTrees = 0;
        for (int i = 0; i < roots.size(); i++) {
            try {
                for (UUID id : identities.get(i)) if (counts.get(id) > 1) throw new IOException("Duplicate entity UUID " + id);
                int[] players = {0};
                EntityTag tree = entity(roots.get(i), new HashSet<>(), players);
                if (players[0] != 0) throw new IOException("Player in entity tree");
                checkChunk(tree, x, z);
                rejectLeash(roots.get(i));
                kept.add(tree);
            } catch (IOException e) {
                skippedTrees++;
                skipped.addAll(identities.get(i));
                issues.add("Skipped entity tree " + identities.get(i) + ": " + e.getMessage());
            }
        }
        var template = new EntityChunkSnapshot(raw, List.of(), 0, version == null ? -1 : (int) version.value());
        return new Recovery(withEntities(template, kept), Set.copyOf(skipped), List.copyOf(issues), skippedTrees, unknown[0]);
    }

    private static void gather(Tag tree, Set<UUID> ids, Map<UUID, Integer> counts, boolean[] unknown) {
        try {
            Map<String, Tag> fields = compound(tree);
            try { UUID id = uuid(fields); ids.add(id); counts.merge(id, 1, Integer::sum); }
            catch (IOException e) { unknown[0] = true; }
            for (Tag child : compounds(fields.get("Passengers"), "Passengers")) gather(child, ids, counts, unknown);
        } catch (IOException e) { unknown[0] = true; }
    }

    private static void rejectLeash(Tag tree) throws IOException {
        Map<String, Tag> fields = compound(tree);
        if (fields.containsKey("Leash")) throw new IOException("Leashed entity rollback is not supported");
        for (Tag child : compounds(fields.get("Passengers"), "Passengers")) rejectLeash(child);
    }

    public static Set<UUID> treeUuids(EntityTag tree) {
        Set<UUID> ids = new HashSet<>(); collectUuids(tree, ids); return ids;
    }

    public static EntityChunkSnapshot withEntities(EntityChunkSnapshot template, List<EntityTag> entities) throws IOException {
        Tag root = parse(template.nbt());
        List<Tag> tags = new ArrayList<>();
        for (EntityTag entity : entities) tags.add(parse(entity.nbt()));
        compound(root).put("Entities", new Tag(9, new TagList(10, tags)));
        return new EntityChunkSnapshot(write(root), entities, template.skippedPlayers(), template.dataVersion());
    }

    private static EntityChunkSnapshot decode(byte[] rawChunk, int chunkX, int chunkZ) throws IOException {
        Tag root = parse(rawChunk, "Entity chunk must be a compound");
        Map<String, Tag> fields = compound(root);
        Tag position = fields.get("Position");
        if (position != null && (position.type() != 11 || ((int[]) position.value()).length != 2
                || ((int[]) position.value())[0] != chunkX || ((int[]) position.value())[1] != chunkZ)) {
            throw new IOException("Entity chunk Position differs from [" + chunkX + ", " + chunkZ + "]");
        }
        Set<UUID> uuids = new HashSet<>();
        int[] skipped = {0};
        List<EntityTag> entities = new ArrayList<>();
        List<Tag> kept = new ArrayList<>();
        for (Tag entity : compounds(fields.get("Entities"), "Entities")) {
            EntityTag parsed = entity(entity, uuids, skipped);
            if (parsed != null) {
                checkChunk(parsed, chunkX, chunkZ);
                entities.add(parsed);
                kept.add(entity);
            }
        }
        fields.put("Entities", new Tag(9, new TagList(10, kept)));
        Tag version = fields.get("DataVersion");
        if (version != null && version.type() != 3) throw new IOException("Invalid entity DataVersion");
        return new EntityChunkSnapshot(write(root), entities, skipped[0], version == null ? -1 : (int) version.value());
    }

    public static EntityChunkSnapshot empty(int x, int z) throws IOException {
        Map<String, Tag> fields = new LinkedHashMap<>();
        fields.put("Position", new Tag(11, new int[]{x, z}));
        fields.put("Entities", new Tag(9, new TagList(10, List.of())));
        return new EntityChunkSnapshot(write(new Tag(10, fields)), List.of(), 0, -1);
    }

    private static void checkChunk(EntityTag entity, int x, int z) throws IOException {
        if ((int)Math.floor(entity.x / 16) != x || (int)Math.floor(entity.z / 16) != z)
            throw new IOException("Entity/passenger " + entity.uuid + " is outside target chunk");
        for (EntityTag child : entity.passengers) checkChunk(child, x, z);
    }

    public static Set<UUID> uuids(EntityChunkSnapshot snapshot) {
        Set<UUID> ids = new HashSet<>();
        for (EntityTag entity : snapshot.entities) collectUuids(entity, ids);
        return Set.copyOf(ids);
    }
    private static void collectUuids(EntityTag entity, Set<UUID> ids) {
        ids.add(entity.uuid);
        for (EntityTag child : entity.passengers) collectUuids(child, ids);
    }

    private static EntityTag entity(Tag tag, Set<UUID> uuids, int[] skipped) throws IOException {
        Map<String, Tag> fields = compound(tag);
        Tag idTag = fields.getOrDefault("id", fields.get("Id"));
        if (idTag == null || idTag.type() != 8 || !((String) idTag.value()).matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IOException("Missing or invalid entity id");
        String id = (String) idTag.value();
        if (id.equals("minecraft:player")) { skipped[0]++; return null; }
        fields.put("id", idTag);
        fields.remove("Id");
        UUID uuid = uuid(fields);
        if (!uuids.add(uuid)) throw new IOException("Duplicate entity UUID " + uuid);
        double[] pos = numbers(fields.get("Pos"), 6, 3, "Pos for " + id + " " + uuid);
        double[] rot = numbers(fields.get("Rotation"), 5, 2, "Rotation for " + id + " " + uuid);
        if (Math.abs(pos[0]) > 30_000_000 || Math.abs(pos[2]) > 30_000_000 || Math.abs(pos[1]) > 20_000_000)
            throw new IOException("Entity position out of bounds: " + uuid);
        fields.put("UUID", new Tag(11, new int[]{(int)(uuid.getMostSignificantBits() >> 32),
                (int)uuid.getMostSignificantBits(), (int)(uuid.getLeastSignificantBits() >> 32), (int)uuid.getLeastSignificantBits()}));
        fields.remove("UUIDMost"); fields.remove("UUIDLeast");
        List<EntityTag> children = new ArrayList<>();
        List<Tag> kept = new ArrayList<>();
        for (Tag child : compounds(fields.get("Passengers"), "Passengers")) {
            EntityTag parsed = entity(child, uuids, skipped);
            if (parsed != null) { children.add(parsed); kept.add(child); }
        }
        if (fields.containsKey("Passengers")) fields.put("Passengers", new Tag(9, new TagList(10, kept)));
        return new EntityTag(id, uuid, pos[0], pos[1], pos[2], (float)rot[0], (float)rot[1], write(tag), children);
    }

    private static UUID uuid(Map<String, Tag> fields) throws IOException {
        Tag value = fields.get("UUID");
        if (value != null && value.type() == 11 && ((int[])value.value()).length == 4) {
            int[] v = (int[])value.value();
            return new UUID(((long)v[0] << 32) | Integer.toUnsignedLong(v[1]), ((long)v[2] << 32) | Integer.toUnsignedLong(v[3]));
        }
        if (value != null && value.type() == 8) {
            try { return UUID.fromString((String)value.value()); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid UUID string", e); }
        }
        Tag most = fields.get("UUIDMost"), least = fields.get("UUIDLeast");
        if (value == null && most != null && least != null && most.type() == 4 && least.type() == 4)
            return new UUID((long)most.value(), (long)least.value());
        throw new IOException("Missing or invalid entity UUID");
    }

    private static double[] numbers(Tag tag, int type, int size, String name) throws IOException {
        if (tag == null || tag.type() != 9 || ((TagList)tag.value()).type() != type || ((TagList)tag.value()).tags().size() != size)
            throw new IOException("Missing or invalid " + name);
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = ((Number)((TagList)tag.value()).tags().get(i).value()).doubleValue();
            if (!Double.isFinite(values[i])) throw new IOException("Non-finite " + name);
        }
        return values;
    }

}
