package io.github.truscdo.foldbackup.mod.rollback;

import io.github.truscdo.foldbackup.core.LogUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Rebuilds the target chunk's POI records after a chunk rollback.
 * <p>
 * The rebuilt records come from the restored block sections, not from the snapshot
 * POI data. New records start with their type's full ticket count; tickets are then
 * consumed by the loaded villagers whose memories point at records in the target
 * chunk. Villagers outside the three-chunk radius, villagers in unloaded chunks, and
 * modded memories are treated as not holding a ticket, as defined by the design.
 * <p>
 * Only the target chunk's POI column is read; records outside it are neither read
 * nor written.
 */
final class PoiRollbackSupport {

    private static final LogUtil LOG = LogUtil.getLogger("foldbackup-rollback");

    /** Three chunks around the target chunk, matching the villager POI search range. */
    private static final int HOLDER_RADIUS_BLOCKS = 48;

    private PoiRollbackSupport() {
    }

    static void rebuild(ServerLevel level, LevelChunk chunk) {
        PoiManager poiManager = level.getPoiManager();
        ChunkPos chunkPos = chunk.getPos();

        int removed = removeExistingRecords(poiManager, chunkPos);
        int added = rebuildFromSections(level, chunk, poiManager);
        Map<BlockPos, Integer> holders = countHolders(level, chunkPos, poiManager);
        int occupied = takeTickets(poiManager, chunkPos, holders);

        LOG.info("Chunk [{}, {}]: POI rebuilt (removed={}, added={}, holderPositions={}, tickets={})",
                chunkPos.x, chunkPos.z, removed, added, holders.size(), occupied);
    }

    private static int removeExistingRecords(PoiManager poiManager, ChunkPos chunkPos) {
        List<BlockPos> positions = poiManager
                .getInChunk(type -> true, chunkPos, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .distinct()
                .toList();
        for (BlockPos pos : positions) {
            poiManager.remove(pos);
        }
        return positions.size();
    }

    private static int rebuildFromSections(ServerLevel level, LevelChunk chunk, PoiManager poiManager) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = level.getMinSection();
        int added = 0;
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || !section.maybeHas(PoiTypes::hasPoi)) continue;
            SectionPos sectionPos = SectionPos.of(chunk.getPos(), minSection + index);
            Iterator<BlockPos> positions = sectionPos.blocksInside().iterator();
            while (positions.hasNext()) {
                BlockPos pos = positions.next();
                BlockState state = section.getBlockState(
                        SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ()));
                var type = PoiTypes.forState(state);
                if (type.isPresent()) {
                    poiManager.add(pos, type.get());
                    added++;
                }
            }
        }
        return added;
    }

    private static Map<BlockPos, Integer> countHolders(ServerLevel level, ChunkPos chunkPos, PoiManager poiManager) {
        AABB area = new AABB(
                chunkPos.getMinBlockX() - HOLDER_RADIUS_BLOCKS,
                level.getMinBuildHeight(),
                chunkPos.getMinBlockZ() - HOLDER_RADIUS_BLOCKS,
                chunkPos.getMaxBlockX() + 1 + HOLDER_RADIUS_BLOCKS,
                level.getMaxBuildHeight(),
                chunkPos.getMaxBlockZ() + 1 + HOLDER_RADIUS_BLOCKS);
        List<Villager> villagers = level.getEntitiesOfClass(
                Villager.class, area, villager -> !villager.isRemoved());

        Map<BlockPos, Integer> counts = new HashMap<>();
        for (Villager villager : villagers) {
            Set<BlockPos> counted = new HashSet<>();
            for (Map.Entry<MemoryModuleType<GlobalPos>, BiPredicate<Villager, Holder<PoiType>>> memory
                    : Villager.POI_MEMORIES.entrySet()) {
                var position = villager.getBrain().getMemory(memory.getKey());
                if (position.isEmpty()) continue;
                GlobalPos globalPos = position.get();
                if (!globalPos.dimension().equals(level.dimension())) continue;
                BlockPos pos = globalPos.pos();
                if ((pos.getX() >> 4) != chunkPos.x || (pos.getZ() >> 4) != chunkPos.z) continue;
                var type = poiManager.getType(pos);
                if (type.isEmpty() || !memory.getValue().test(villager, type.get())) continue;
                // One villager holds at most one ticket at a given position.
                if (counted.add(pos)) counts.merge(pos, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Occupies tickets on the records rebuilt inside the target chunk.
     * <p>
     * The rebuilt records already hold their type's full ticket count, so consuming a
     * ticket is all that is needed. {@code PoiManager.take} cannot express "this exact
     * block": its radius argument is converted to chunk granularity, so a radius of
     * zero scans a 3x3 chunk neighbourhood and may load neighbour POI columns from
     * disk on the server thread. Addressing the target column's records directly keeps
     * the rollback from touching anything outside it.
     */
    private static int takeTickets(PoiManager poiManager, ChunkPos chunkPos, Map<BlockPos, Integer> counts) {
        if (counts.isEmpty()) return 0;

        Map<BlockPos, PoiRecord> records = new HashMap<>();
        poiManager.getInChunk(type -> true, chunkPos, PoiManager.Occupancy.ANY)
                .forEach(record -> records.putIfAbsent(record.getPos(), record));

        int occupied = 0;
        for (Map.Entry<BlockPos, Integer> entry : counts.entrySet()) {
            PoiRecord record = records.get(entry.getKey());
            if (record == null) continue;
            for (int i = 0; i < entry.getValue(); i++) {
                if (record.acquireTicket()) occupied++;
            }
        }
        return occupied;
    }
}
