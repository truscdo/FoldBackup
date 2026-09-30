package io.github.truscdo.foldbackup.mod.rollback;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.format.EntityRegionCodec.EntityChunkSnapshot;
import io.github.truscdo.foldbackup.core.format.EntityRegionCodec;
import io.github.truscdo.foldbackup.mod.ChunkEntityQuery;
import io.github.truscdo.foldbackup.mod.SnapshotNbt;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.util.*;

/** 服务器线程上的实体替换。准备阶段必须在任何方块或实体变更之前完成。 */
public final class EntityRollbackBridge {
    private static final LogUtil LOG = LogUtil.getLogger("foldbackup-entities");

    public Prepared prepare(ServerLevel level, ChunkPos chunk, EntityChunkSnapshot snapshot) throws IOException {
        return prepare(level, chunk, EntityRegionCodec.recover(snapshot.nbt(), chunk.x, chunk.z));
    }

    public Prepared prepare(ServerLevel level, ChunkPos chunk, EntityRegionCodec.Recovery recovery) throws IOException {
        checkThread(level);
        var snapshot = recovery.snapshot();
        if (recovery.preserveAll()) return preserve(level, chunk, "Entity identity could not be determined; " + recovery.issues());
        if (!level.areEntitiesLoaded(chunk.toLong())) throw new IOException("Entities are not loaded for " + chunk + "; retry after loading");
        if (snapshot.dataVersion() > SharedConstants.getCurrentVersion().getDataVersion().getVersion())
            throw new IOException("Entity snapshot is from a newer Minecraft version");

        List<Entity> current = ChunkEntityQuery.nonPlayers(level, chunk);
        current = new ArrayList<>(current);
        List<String> issues = new ArrayList<>(recovery.issues());
        Set<UUID> protectedIds = new HashSet<>(recovery.skippedIds());
        Set<Entity> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        targets.addAll(current);
        for (Entity entity : current) {
            if ((entity.getVehicle() != null && !targets.contains(entity.getVehicle()))
                    || entity.getPassengers().stream().anyMatch(e -> !targets.contains(e))) {
                protectedIds.add(entity.getUUID());
                issues.add("Preserved passenger relationship crossing chunk/player boundary: " + describe(entity));
            }
        }

        List<Entity> replacement = new ArrayList<>();
        Set<UUID> currentIds = new HashSet<>();
        current.forEach(e -> currentIds.add(e.getUUID()));
        int skippedTrees = recovery.skippedTrees();
        for (var tag : snapshot.entities()) {
            Set<UUID> ids = EntityRegionCodec.treeUuids(tag);
            try {
                for (UUID id : ids) for (ServerLevel other : level.getServer().getAllLevels()) {
                    if (other.entityManager.isLoaded(id) && (other != level || !currentIds.contains(id)))
                        throw new IOException("Entity UUID conflict outside target chunk: " + id + " in " + other.dimension().location());
                }
                replacement.add(load(SnapshotNbt.read(tag.nbt()), level, chunk, new HashSet<>()));
            } catch (IOException | RuntimeException e) {
                protectedIds.addAll(ids); skippedTrees++;
                issues.add("Skipped entity tree " + ids + ": " + e.getMessage());
            }
        }

        // Capture recoverable current trees before deciding what can safely be removed.
        Map<Entity, Entity> captured = new IdentityHashMap<>();
        for (Entity entity : current) {
            if (entity.isPassenger()) continue;
            Set<UUID> ids = new HashSet<>();
            entity.getSelfAndPassengers().forEach(e -> ids.add(e.getUUID()));
            if (!Collections.disjoint(ids, protectedIds)) continue;
            try {
                CompoundTag nbt = new CompoundTag();
                if (!entity.save(nbt)) throw new IOException("Cannot capture current entity");
                Set<UUID> savedIds = new HashSet<>();
                Entity saved = load(nbt, level, chunk, savedIds);
                if (!savedIds.equals(ids)) throw new IOException("Current passenger tree could not be fully serialized");
                captured.put(entity, saved);
            } catch (IOException | RuntimeException e) {
                protectedIds.addAll(ids);
                issues.add("Preserved current tree " + ids + ": " + e.getMessage());
            }
        }

        // Expand protection through both current and incoming trees until no overlap remains.
        boolean changed;
        do {
            changed = false;
            for (Entity entity : current) {
                List<Entity> tree = entity.getRootVehicle().getSelfAndPassengers().toList();
                if (tree.stream().anyMatch(e -> protectedIds.contains(e.getUUID())))
                    for (Entity member : tree) changed |= protectedIds.add(member.getUUID());
            }
            for (Iterator<Entity> it = replacement.iterator(); it.hasNext();) {
                Entity root = it.next();
                List<Entity> tree = root.getSelfAndPassengers().toList();
                if (tree.stream().anyMatch(e -> protectedIds.contains(e.getUUID()))) {
                    for (Entity member : tree) changed |= protectedIds.add(member.getUUID());
                    it.remove(); skippedTrees++;
                    issues.add("Skipped entity tree overlapping preserved entities: " + describe(root));
                }
            }
        } while (changed);
        current.removeIf(e -> protectedIds.contains(e.getUUID()));
        currentIds.clear(); current.forEach(e -> currentIds.add(e.getUUID()));

        // 在移除任何实体之前，预先序列化并预加载回退实体。
        List<Entity> fallback = new ArrayList<>();
        for (Entity entity : current) {
            if (entity.isPassenger()) continue;
            fallback.add(Objects.requireNonNull(captured.get(entity)));
        }
        if (snapshot.skippedPlayers() != 0) LOG.warn("Skipped {} player tags in entity snapshot for {}", snapshot.skippedPlayers(), chunk);
        long restored = replacement.stream().mapToLong(e -> e.getSelfAndPassengers().count()).sum();
        long removed = current.size();
        int preserved = targets.size() - current.size();
        String report = "entities restored=" + restored
                + ", removed=" + removed + ", skipped trees=" + skippedTrees + ", preserved=" + preserved;
        if (!issues.isEmpty()) { report += "; " + String.join("; ", issues); LOG.warn("{}: {}", chunk, report); }
        return new Prepared(level, chunk, current, replacement, fallback, report,
                new Summary(restored, removed, skippedTrees, preserved, false));
    }

    public static Prepared preserve(ServerLevel level, ChunkPos chunk, String reason) {
        String report = "entities restored=0, skipped=all (count unknown), current entities preserved: " + reason;
        LOG.warn("{}: {}", chunk, report);
        return new Prepared(level, chunk, List.of(), List.of(), List.of(), report,
                new Summary(0, 0, -1, -1, true));
    }

    private static Entity load(CompoundTag tag, ServerLevel level, ChunkPos chunk, Set<UUID> ids) throws IOException {
        String detail = tag.getString("id") + " UUID=" + tag.get("UUID") + " Pos=" + tag.get("Pos");
        if (tag.getString("id").equals("minecraft:player")) throw new IOException("Player in passenger tree: " + detail);
        // 原版会延迟恢复拴绳，且可能静默丢失目标；因此在变更前直接失败。
        if (tag.contains("Leash")) throw new IOException("Leashed entity rollback is not supported: " + detail);
        Entity entity;
        try {
            entity = EntityType.create(tag, level).orElseThrow(() -> new IOException("Unknown or invalid entity: " + detail));
        } catch (RuntimeException e) { throw new IOException("Cannot load entity " + detail, e); }
        if (entity instanceof Player || !tag.hasUUID("UUID") || !entity.getUUID().equals(tag.getUUID("UUID"))
                || !ids.add(entity.getUUID())) throw new IOException("Invalid or duplicate entity identity: " + detail);
        if (!entity.chunkPosition().equals(chunk)) throw new IOException("Entity/passenger crosses target chunk boundary: " + detail);
        for (var child : tag.getList("Passengers", 10)) {
            Entity passenger = load((CompoundTag)child, level, chunk, ids);
            if (!passenger.startRiding(entity, true)) throw new IOException("Cannot restore passenger relationship: " + detail);
        }
        return entity;
    }

    private static String describe(Entity e) { return EntityType.getKey(e.getType()) + " UUID=" + e.getUUID() + " Pos=" + e.position(); }
    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Entity rollback requires the server thread");
    }

    public static final class Prepared {
        private final ServerLevel level;
        private final ChunkPos chunk;
        private final List<Entity> current, replacement, fallback;
        private boolean used;
        private final String report;
        private final Summary summary;
        public String report() { return report; }
        public Summary summary() { return summary; }
        private Prepared(ServerLevel level, ChunkPos chunk, List<Entity> current, List<Entity> replacement,
                List<Entity> fallback, String report, Summary summary) {
            this.level = level; this.chunk = chunk; this.current = current; this.replacement = replacement; this.fallback = fallback;
            this.report = report;
            this.summary = summary;
        }

        public void apply() throws IOException {
            checkThread(level);
            if (used) throw new IllegalStateException("Entity replacement already attempted");
            used = true;
            List<Entity> created = flatten(replacement);
            try {
                for (Entity entity : current) entity.discard();
                add(created);
                LOG.info("Replaced {} entities with {} in {}", current.size(), created.size(), chunk);
            } catch (Exception failure) {
                try {
                    for (Entity entity : created) if (!entity.isRemoved()) entity.discard();
                    // 移除回调可能中途失败；恢复前先清除仍存活的原有实体。
                    for (Entity entity : current) if (!entity.isRemoved()) entity.discard();
                    add(flatten(fallback));
                } catch (Exception recovery) {
                    failure.addSuppressed(recovery);
                    LOG.error("Entity replacement and recovery failed in {}", chunk, failure);
                    throw new IOException("Entity replacement failed; recovery incomplete in " + chunk + ": " + failure.getMessage(), failure);
                }
                LOG.error("Entity replacement failed in {}; previous entities restored", chunk, failure);
                throw new IOException("Entity replacement failed; previous entities restored: " + failure.getMessage(), failure);
            }
        }

        private void add(List<Entity> entities) throws IOException {
            for (Entity entity : entities) {
                if (!level.addFreshEntity(entity)) throw new IOException("Entity registration rejected: " + describe(entity));
            }
        }
        private static List<Entity> flatten(List<Entity> roots) {
            return roots.stream().flatMap(Entity::getSelfAndPassengers).toList();
        }
    }

    public record Summary(long restored, long removed, int skippedTrees, int preserved, boolean allPreserved) {
    }
}
