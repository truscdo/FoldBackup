package io.github.truscdo.foldbackup.mod.rollback;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.DimensionPath;
import io.github.truscdo.foldbackup.mod.SnapshotNbt;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * 处理内存中区块回档（方案二），适用于当前已加载、无法通过文件替换方式
 * 修改的区块（因为 RegionFile 持有文件句柄）。
 * <p>
 * <b>FAWE 风格方案：</b>读取快照 NBT，通过 {@link ChunkSerializer#read} 反序列化，
 * 使用当前世界的 BlockState 注册表重建 {@link LevelChunkSection} 对象，
 * 然后通过 AT 原子性地交换到活动 {@link LevelChunk#sections} 数组中。
 * <p>
 * 本类位于 {@code mod/} 层，因为它直接依赖 Minecraft API（{@link LevelChunk}、
 * {@link ChunkSerializer} 等），且必须在服务器主线程上运行。
 */
public class InMemoryChunkRollbackHandler {

    private static final LogUtil LOG = LogUtil.getLogger("foldbackup-rollback");

    private final SnapshotStore snapshotManager;
    private final BlobStore blobStore;

    public InMemoryChunkRollbackHandler(SnapshotStore snapshotManager, BlobStore blobStore) {
        this.snapshotManager = snapshotManager;
        this.blobStore = blobStore;
    }

    /**
     * 通过替换内存数据来回滚单个已加载的区块。
     *
     * @param level      区块所在的维度
     * @param chunkX     全局区块 X 坐标
     * @param chunkZ     全局区块 Z 坐标
     * @param epochSeconds 要恢复的快照 Epoch，或 {@code null} 表示使用最新的
     * @return 操作结果
     */
    public RollbackOutcome rollbackChunk(
            ServerLevel level, int chunkX, int chunkZ, @Nullable Long epochSeconds) {
        if (!level.getServer().isSameThread()) return failure("foldbackup.rollback.failure.server_thread");
        // ── 步骤 1-3：解析快照 + 区域条目 + 解析 .lrh 数据块 ──
        ChunkRollbackSupport.ResolvedSnapshot resolved;
        try {
            resolved = ChunkRollbackSupport.resolveChunk(snapshotManager, blobStore,
                    chunkX, chunkZ, epochSeconds, DimensionPath.forLevel(level));
        } catch (IOException e) {
            return failure("foldbackup.rollback.failure.snapshot_read", e);
        }

        // ── 步骤 4：提取目标区块的 NBT ──
        ChunkData snapshotChunkData = resolved.chunk();
        if (snapshotChunkData == null) {
            return failure("foldbackup.rollback.failure.chunk_empty",
                    chunkX, chunkZ, resolved.manifest().getSnapshotId());
        }

        CompoundTag chunkNbt;
        try {
            chunkNbt = SnapshotNbt.read(snapshotChunkData.getRawChunk());
        } catch (IOException e) {
            return failure("foldbackup.rollback.failure.snapshot_read", e);
        }

        // 在 ChunkSerializer.read 可能触及 POI 之前，完成全部实体校验。
        EntityRollbackBridge.Prepared entities;
        try {
            var entitySnapshot = ChunkRollbackSupport.recoverEntities(resolved.manifest(), blobStore,
                    chunkX, chunkZ, DimensionPath.forLevel(level));
            entities = new EntityRollbackBridge().prepare(level, new ChunkPos(chunkX, chunkZ), entitySnapshot);
        } catch (Exception e) {
            entities = EntityRollbackBridge.preserve(level, new ChunkPos(chunkX, chunkZ), e.getMessage());
        }

        // ── 步骤 5：反序列化为临时 ChunkAccess ──
        ChunkAccess sourceChunk;
        try {
            var chunkMap = level.getChunkSource().chunkMap;
            sourceChunk = ChunkSerializer.read(level, level.getPoiManager(), chunkMap.storageInfo(),
                    new ChunkPos(chunkX, chunkZ), chunkNbt);
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.chunk_deserialize", e);
        }

        // ── 步骤 6：获取活动区块 ──
        LevelChunk liveChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (liveChunk == null) {
            // 游戏内仅支持加载区块；未加载区块应停服后使用 CLI。
            return failure("foldbackup.rollback.failure.chunk_not_loaded", chunkX, chunkZ);
        }

        // ── 步骤 7：先清除 POI（在 sections/BEs 变更之前） ──
        try {
            fixupPoi(level, chunkX, chunkZ);
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.poi", e);
        }

        // ── 步骤 8：替换 section ──
        try {
            replaceSections(liveChunk, sourceChunk, level);
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.sections", e);
        }

        // ── 步骤 9：替换方块实体 ──
        // （setBlockEntity 会自动为新 BE 注册 POI）
        // 注意：我们传入原始 chunkNbt，因为 ImposterProtoChunk 的
        // getBlockEntitiesPos()/getBlockEntity() 无法正确委派到包装的
        // ProtoChunk 的 BE 映射——直接从 NBT 读取是获取快照方块实体的
        // 唯一可靠方式。
        try {
            replaceBlockEntities(liveChunk, sourceChunk, level, chunkNbt);
            // 为所有 BE 注册刻更新和正确的生命周期——批量 BE 替换后的标准做法（见 §3.3）。
            liveChunk.registerAllBlockEntitiesAfterLevelLoad();
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.block_entities", e);
        }

        // 应用在世界变更之前准备好的实体替换。
        try {
            liveChunk.setUnsaved(true);
            entities.apply();
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.entities", e);
        }

        // ── 步骤 10：环境修复 ──
        try {
            // 标记光照需要重新计算——false 告诉 MC 内置光照引擎处理排队数据；true 则会跳过。
            liveChunk.setLightCorrect(false);
            fixupLighting(level, chunkX, chunkZ);
            syncClients(level, liveChunk, chunkX, chunkZ);
            liveChunk.setUnsaved(true);
        } catch (Exception e) {
            return failure("foldbackup.rollback.failure.environment", e);
        }

        EntityRollbackBridge.Summary entitySummary = entities.summary();
        if (entitySummary.allPreserved()) {
            return new RollbackOutcome(true, "foldbackup.rollback.success_entities_preserved",
                    new Object[] { chunkX, chunkZ, resolved.manifest().getSnapshotId(), entitySummary.removed() });
        }
        return new RollbackOutcome(true, "foldbackup.rollback.success",
                new Object[] { chunkX, chunkZ, resolved.manifest().getSnapshotId(),
                        entitySummary.restored(), entitySummary.removed(), entitySummary.skippedTrees(),
                        entitySummary.preserved() });
    }

    // ── section 替换（写时复制） ──

    private void replaceSections(LevelChunk liveChunk, ChunkAccess sourceChunk,
            ServerLevel level) {
        LevelChunkSection[] sourceSections = sourceChunk.getSections();
        LevelChunkSection[] liveSections = liveChunk.getSections();

        int sectionCount = level.getSectionsCount();

        // 验证数组长度匹配
        if (sourceSections.length != sectionCount || liveSections.length != sectionCount) {
            throw new IllegalStateException(
                    "Section count mismatch: source=" + sourceSections.length
                            + " live=" + liveSections.length + " expected=" + sectionCount);
        }

        // 写时复制：构建新数组，使持有旧数组引用的读者（例如区块发送）不会看到部分修改的状态。
        LevelChunkSection[] newSections = new LevelChunkSection[sectionCount];
        System.arraycopy(liveSections, 0, newSections, 0, sectionCount);

        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection sourceSec = sourceSections[i];
            if (sourceSec == null) {
                // 快照中该 section 没有数据（= NBT 省略了全空气区域）。
                // 创建全空气 section，使回档清除备份后放置的任何方块。
                PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                        Block.BLOCK_STATE_REGISTRY,
                        Blocks.AIR.defaultBlockState(),
                        PalettedContainer.Strategy.SECTION_STATES);
                // 尽可能保留活动区块的生物群系，否则从源借用
                PalettedContainerRO<Holder<Biome>> biomes;
                if (liveSections[i] != null) {
                    biomes = liveSections[i].getBiomes();
                } else {
                    biomes = firstNonNullBiomes(sourceSections);
                }
                newSections[i] = new LevelChunkSection(airStates, biomes);
                continue;
            }

            // 1.21.1 的 copy() 会保留调色板指向源容器的扩容回调。
            // 对副本写入新状态时会扩容源容器，而副本仍使用旧存储；
            // 全空气 section 因此会向 ZeroBitStorage 写入非零 ID 并抛异常。
            // 新建容器并逐项填充，使所有调色板的扩容回调绑定到新容器。
            PalettedContainer<BlockState> newStates = copyWritableStates(sourceSec.getStates());

            // 保留快照 section 的生物群系数据（回档包含生物群系）
            PalettedContainerRO<Holder<Biome>> newBiomes = sourceSec.getBiomes();

            // 使用正确的方块计数构建新的 LevelChunkSection。
            // 即使源 section 是 hasOnlyAir()，我们仍然替换——
            // 这确保完整的回档，清除备份后添加的任何方块。
            LevelChunkSection newSection = new LevelChunkSection(newStates, newBiomes);

            newSections[i] = newSection;
        }

        // 原子性地交换整个数组引用
        liveChunk.sections = newSections;
    }

    static PalettedContainer<BlockState> copyWritableStates(PalettedContainer<BlockState> source) {
        BlockState initial = source.get(0, 0, 0);
        PalettedContainer<BlockState> result = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, initial, PalettedContainer.Strategy.SECTION_STATES);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = source.get(x, y, z);
                    if (state != initial) result.set(x, y, z, state);
                }
            }
        }
        return result;
    }

    /**
     * 从第一个非 null 的源 section 查找生物群系容器。
     * 当 {@code sourceSections[i]} 为 null 且该索引位置也没有活动 section 时的回退方案。
     */
    private static PalettedContainerRO<Holder<Biome>> firstNonNullBiomes(
            LevelChunkSection[] sourceSections) {
        for (LevelChunkSection sec : sourceSections) {
            if (sec != null) {
                return sec.getBiomes();
            }
        }
        // 所有 section 都是 null——对于有效区块来说不可能，但作为安全回退：
        throw new IllegalStateException("All source sections are null — corrupted snapshot?");
    }

    // ── 方块实体替换 ──

    private void replaceBlockEntities(LevelChunk liveChunk, ChunkAccess sourceChunk,
            ServerLevel level, CompoundTag chunkNbt) {
        // 阶段 A：从活动区块中移除所有现有的方块实体
        // 我们对 keySet 的快照进行迭代，以避免并发修改
        var existingBEs = liveChunk.getBlockEntities();
        if (!existingBEs.isEmpty()) {
            java.util.Set.copyOf(existingBEs.keySet()).forEach(pos -> {
                var be = liveChunk.getBlockEntity(pos);
                if (be != null) {
                    be.setRemoved();
                }
                liveChunk.removeBlockEntity(pos);
            });
        }

        // 阶段 B：直接从快照 NBT 加载方块实体
        // 实现说明：
        // ChunkSerializer.read() 返回 ImposterProtoChunk，其
        // getBlockEntitiesPos()/getBlockEntity() 无法正确委派到包装的
        // ProtoChunk 的 BE 存储。从原始区块 NBT 的 "block_entities"
        // 列表读取是可靠的方法。
        var blockEntitiesList = chunkNbt.getList("block_entities", 10); // 类型 10 即 Tag.TAG_COMPOUND
        for (int i = 0; i < blockEntitiesList.size(); i++) {
            CompoundTag beTag = blockEntitiesList.getCompound(i);
            net.minecraft.core.BlockPos bePos = new net.minecraft.core.BlockPos(
                    beTag.getInt("x"), beTag.getInt("y"), beTag.getInt("z"));
            BlockState blockState = sourceChunk.getBlockState(bePos);
            // BlockEntity.loadStatic 使用标签的 "id" 字段查找注册的
            // BlockEntityType 并相应地进行反序列化。
            BlockEntity be = BlockEntity.loadStatic(bePos, blockState, beTag, level.registryAccess());
            if (be == null) {
                LOG.warn("  Skipping unknown BE at ({},{},{}) id={}",
                        beTag.getInt("x"), beTag.getInt("y"), beTag.getInt("z"),
                        beTag.getString("id"));
                continue;
            }
            // 设置世界引用，使 BE 知道其所属世界
            be.setLevel(level);
            // 标记为未移除（新加载状态）
            be.clearRemoved();
            // 调用 onLoad() 让 BE 初始化（例如开始刻更新）
            be.onLoad();
            // 注册到区块——这也处理刻更新器的注册
            liveChunk.setBlockEntity(be);
        }

        // 阶段 C：数据附件——默认从活动区块保留
        // （无需操作：附件存在于 LevelChunk 对象本身上，我们不会替换它。
        // 快照的附件数据被丢弃。）
    }

    // ── 环境修复 ──

    private void fixupLighting(ServerLevel level, int chunkX, int chunkZ) {
        LevelLightEngine lightEngine = level.getLightEngine();
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        // 阶段 A：清除此区块中所有 section 的现有光照数据
        // 这会强制光照引擎完全重新计算，而不是假设缓存值仍然有效
        // （在 section 替换后可能已失效）。
        int minSection = level.getMinBuildHeight() >> 4;
        int maxSection = (level.getMaxBuildHeight() - 1) >> 4;
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(chunkX, sectionY, chunkZ);
            lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
            lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
        }

        // 阶段 B：安排异步光照重新计算
        if (lightEngine instanceof ThreadedLevelLightEngine threaded) {
            threaded.updateChunkStatus(pos);
            threaded.tryScheduleUpdate();
        }
    }

    private void fixupPoi(ServerLevel level, int chunkX, int chunkZ) {
        // 清除 POI 缓存——NeoForge 补丁添加了 SectionStorage.remove()
        // 我们直接使用 PoiManager，因为它扩展了 SectionStorage
        // 清除 POI 缓存——遍历此区块中的所有 section 并逐个移除
        var poiManager = level.getPoiManager();
        int minSection = level.getMinBuildHeight() >> 4;
        int maxSection = (level.getMaxBuildHeight() - 1) >> 4;
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            long sectionPos = net.minecraft.core.SectionPos.asLong(chunkX, sectionY, chunkZ);
            poiManager.remove(sectionPos);
        }
    }

    private void syncClients(ServerLevel level, LevelChunk liveChunk, int chunkX, int chunkZ) {
        // 向追踪此区块的玩家发送更新后的区块
        // ChunkMap.markChunkPendingToSend 通过 AT 使其可访问
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        var chunkMap = level.getChunkSource().chunkMap;
        for (ServerPlayer player : chunkMap.getPlayers(pos, false)) {
            net.minecraft.server.level.ChunkMap.markChunkPendingToSend(player, liveChunk);
        }
    }
    // ── 辅助方法 ──

    private static RollbackOutcome failure(String key, Object... args) {
        return new RollbackOutcome(false, key, args);
    }

    private static RollbackOutcome failure(String key, Exception exception) {
        LOG.error("Chunk rollback failed ({})", key, exception);
        return new RollbackOutcome(false, key, new Object[0]);
    }
}
