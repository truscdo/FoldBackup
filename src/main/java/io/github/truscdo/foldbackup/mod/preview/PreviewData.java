package io.github.truscdo.foldbackup.mod.preview;

import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.rollback.PreviewDifference;
import io.github.truscdo.foldbackup.core.rollback.PreviewDifference.Counts;
import io.github.truscdo.foldbackup.core.rollback.PreviewSnapshotReader;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.format.EntityRegionCodec;
import io.github.truscdo.foldbackup.mod.ChunkEntityQuery;
import io.github.truscdo.foldbackup.mod.SnapshotNbt;
import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.io.IOException;
import java.util.*;

/** 只解析数值数据：绝不构造区块、方块实体或实体对象。 */
public final class PreviewData {
    private static final class Decoder {
        private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
    }

    /** error 非空表示统计不可用，而不是历史实体集为空。 */
    public record EntityData(Map<UUID, CompoundTag> entities,
            ChunkRollbackSupport.EntitySnapshotState state, String error) {
    }

    public record Raw(long epochSeconds, String snapshotId, CompoundTag blocks, EntityData entities) {
    }

    public record State(BlockState[] blocks, Map<BlockPos, CompoundTag> blockEntities) {
    }

    private PreviewData() {
    }

    public static Raw read(SnapshotStore store, BlobStore blobs, ChunkPos pos, Long epochSeconds, String dimension)
            throws IOException {
        var raw = PreviewSnapshotReader.read(store, blobs, pos.x, pos.z, epochSeconds, dimension);
        CompoundTag blocks = SnapshotNbt.read(raw.blocks());
        return new Raw(raw.epochSeconds(), raw.snapshotId(), blocks, decodeEntities(raw));
    }

    private static EntityData decodeEntities(PreviewSnapshotReader.Snapshot raw) {
        if (raw.entityError() != null)
            return new EntityData(Map.of(), null, raw.entityError());
        try {
            var snapshot = raw.entities();
            Map<UUID, CompoundTag> tags = new HashMap<>();
            for (var entity : snapshot.snapshot().entities())
                collectEntity(entity, null, tags);
            return new EntityData(Map.copyOf(tags), snapshot.state(), null);
        } catch (Exception e) {
            return new EntityData(Map.of(), null, "历史实体解析失败: " + e.getMessage());
        }
    }

    public static State decode(Raw raw, ServerLevel level, ChunkPos pos) throws IOException {
        CompoundTag tag = raw.blocks;
        if (!tag.contains("xPos", Tag.TAG_INT) || !tag.contains("zPos", Tag.TAG_INT)
                || tag.getInt("xPos") != pos.x || tag.getInt("zPos") != pos.z)
            throw new IOException("快照区块坐标不匹配");
        if (tag.getInt("DataVersion") != SharedConstants.getCurrentVersion().getDataVersion().getVersion())
            throw new IOException("预览要求快照与当前 Minecraft 数据版本相同");
        if (!tag.getString("Status").equals("minecraft:full"))
            throw new IOException("快照不是完整区块");
        ListTag sections = compoundList(tag, "sections", true);
        ListTag blockEntities = compoundList(tag, "block_entities", false);
        BlockState[] blocks = new BlockState[level.getHeight() * 256];
        Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
        Set<Integer> seen = new HashSet<>();
        for (Tag entry : sections) {
            CompoundTag section = (CompoundTag) entry;
            if (!section.contains("Y", Tag.TAG_BYTE))
                throw new IOException("缺少 section Y");
            int y = section.getByte("Y");
            if (!seen.add(y))
                throw new IOException("重复的 section Y: " + y);
            int index = level.getSectionIndexFromSectionY(y);
            if (!section.contains("block_states"))
                continue;
            if (index < 0 || index >= level.getSectionsCount())
                throw new IOException("快照方块超出当前世界高度: " + y);
            for (Tag paletteEntry : section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                CompoundTag state = (CompoundTag) paletteEntry;
                var id = net.minecraft.resources.ResourceLocation.tryParse(state.getString("Name"));
                if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))
                    throw new IOException("未注册的方块: " + state.getString("Name") + "，section Y=" + y);
                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                var properties = state.getCompound("Properties");
                for (String name : properties.getAllKeys()) {
                    var property = block.getStateDefinition().getProperty(name);
                    if (property == null || property.getValue(properties.getString(name)).isEmpty())
                        throw new IOException("不支持的方块属性: " + id + " " + name + "，section Y=" + y);
                }
            }
            var states = Decoder.STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states"));
            var palette = states.result().orElseThrow(() -> new IOException("无法解析 section " + y + ": "
                    + states.error().map(error -> error.message()).orElse("无效方块调色板")));
            for (int dy = 0; dy < 16; dy++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        blocks[index * 4096 + dy * 256 + z * 16 + x] = palette.get(x, dy, z);
        }
        Map<BlockPos, CompoundTag> bes = new HashMap<>();
        for (Tag entry : blockEntities) {
            CompoundTag be = (CompoundTag) entry;
            BlockPos p = new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z"));
            if (!new ChunkPos(p).equals(pos) || level.isOutsideBuildHeight(p) || bes.put(p, be.copy()) != null)
                throw new IOException("无效或重复的方块实体位置: " + p);
        }
        return new State(blocks, Map.copyOf(bes));
    }

    private static ListTag compoundList(CompoundTag tag, String key, boolean required) throws IOException {
        if (!tag.contains(key)) {
            if (required)
                throw new IOException("快照缺少列表: " + key);
            return new ListTag();
        }
        if (!(tag.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
            throw new IOException("快照列表类型无效: " + key);
        return list;
    }

    private static void collectEntity(EntityRegionCodec.EntityTag entity, UUID vehicle, Map<UUID, CompoundTag> result)
            throws IOException {
        CompoundTag tag = SnapshotNbt.read(entity.nbt());
        tag.remove("Passengers");
        if (vehicle != null)
            tag.putUUID("foldbackup:preview_vehicle", vehicle);
        result.put(entity.uuid(), tag);
        for (var passenger : entity.passengers())
            collectEntity(passenger, entity.uuid(), result);
    }

    /** 在服务器线程上采集方块数据；实体统计另行独立采集。 */
    public static State capture(ServerLevel level, LevelChunk chunk) throws IOException {
        BlockState[] blocks = new BlockState[level.getHeight() * 256];
        for (int i = 0; i < chunk.getSections().length; i++) {
            var section = chunk.getSection(i);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        blocks[i * 4096 + y * 256 + z * 16 + x] = section.getBlockState(x, y, z);
        }
        Map<BlockPos, CompoundTag> bes = new HashMap<>();
        // 读取待加载的方块实体 NBT，但不实例化方块实体对象。
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            var be = chunk.getBlockEntities().get(pos);
            CompoundTag tag = be == null ? chunk.getBlockEntityNbt(pos)
                    : be.saveWithFullMetadata(level.registryAccess());
            if (tag == null)
                throw new IOException("无法读取方块实体: " + pos);
            bes.put(pos.immutable(), tag.copy());
        }
        return new State(blocks, Map.copyOf(bes));
    }

    /** 包含隐藏的非玩家实体；统计失败不得阻断方块预览。 */
    public static Map<UUID, CompoundTag> captureEntities(ServerLevel level, LevelChunk chunk) throws IOException {
        if (!level.areEntitiesLoaded(chunk.getPos().toLong()))
            throw new IOException("实体区块未加载");
        Map<UUID, CompoundTag> entities = new HashMap<>();
        for (var entity : ChunkEntityQuery.nonPlayers(level, chunk.getPos())) {
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            tag.putString("id", net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString());
            tag.remove("Passengers");
            if (entity.getVehicle() != null)
                tag.putUUID("foldbackup:preview_vehicle", entity.getVehicle().getUUID());
            entities.put(entity.getUUID(), tag);
        }
        return Map.copyOf(entities);
    }

    public static Counts blockCounts(State before, State after) {
        return PreviewDifference.blocks(before.blocks, after.blocks, BlockState::isAir);
    }

    public static Counts blockEntityCounts(State before, State after) {
        return PreviewDifference.counts(comparableBlockEntities(before), comparableBlockEntities(after));
    }

    private static Map<BlockPos, CompoundTag> comparableBlockEntities(State state) {
        Map<BlockPos, CompoundTag> tags = new HashMap<>();
        state.blockEntities().forEach((pos, tag) -> {
            CompoundTag copy = tag.copy();
            // 区块存储的延迟加载标记不属于方块实体内容，且不能修改原始 NBT。
            copy.remove("keepPacked");
            tags.put(pos, copy);
        });
        return tags;
    }
}
