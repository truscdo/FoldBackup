package io.github.truscdo.foldbackup.mod.preview;

import com.mojang.serialization.Codec;
import io.github.truscdo.foldbackup.core.backup.RegionProcessor;
import io.github.truscdo.foldbackup.core.format.ChunkData;
import io.github.truscdo.foldbackup.core.format.Region;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.rollback.PreviewDifference;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.core.rollback.PreviewSnapshotReader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.truscdo.foldbackup.core.format.EntityNbtFixture.chunk;
import static io.github.truscdo.foldbackup.core.format.EntityNbtFixture.entity;

class PreviewPreparationTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void malformedEntitiesDoNotPreventReadingBlocks() throws Exception {
        var raw = readSnapshot(new byte[] { 10, 0 });
        assertArrayEquals(new byte[] { 10, 0, 0, 0 }, raw.blocks());
        assertNotNull(raw.entityError());
        assertTrue(raw.entityError().contains("历史实体读取失败"));
        assertNull(raw.entities());
    }

    @Test
    void missingEntitiesRemainAKnownEmptySet() throws Exception {
        var raw = readSnapshot(null);
        assertNull(raw.entityError());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.REGION_MISSING, raw.entities().state());
        assertTrue(raw.entities().snapshot().isEmpty());
    }

    @Test
    void validHistoricalEntitiesRemainAvailableForStatistics() throws Exception {
        UUID id = UUID.randomUUID();
        var raw = readSnapshot(chunk(0, 0, entity(id, 8, 8)));
        assertNull(raw.entityError());
        assertEquals(ChunkRollbackSupport.EntitySnapshotState.PRESENT, raw.entities().state());
        assertTrue(raw.entities().snapshot().entities().stream().anyMatch(e -> e.uuid().equals(id)));
    }

    @Test
    void decodeValidChunkNbtExtractsBlocksBlockEntitiesAndCounts() throws Exception {
        LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        ChunkPos pos = new ChunkPos(0, 0);

        var container = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        container.set(1, 0, 2, Blocks.STONE.defaultBlockState());
        container.set(2, 1, 3, Blocks.GOLD_BLOCK.defaultBlockState());

        CompoundTag chunkTag = validChunkTag(container);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 2);
        chest.putInt("y", 64);
        chest.putInt("z", 3);
        ListTag blockEntities = new ListTag();
        blockEntities.add(chest);
        chunkTag.put("block_entities", blockEntities);

        var raw = new PreviewData.Raw(
                1000L,
                "S-20260910_120000",
                chunkTag,
                new PreviewData.EntityData(Map.of(), ChunkRollbackSupport.EntitySnapshotState.PRESENT, null));
        PreviewData.State target = PreviewData.decode(raw, height, pos);

        int sectionIndex = height.getSectionIndexFromSectionY(4);
        assertEquals(Blocks.STONE.defaultBlockState(),
                target.blocks()[PreviewData.blockIndex(sectionIndex, 1, 0, 2)]);
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(),
                target.blocks()[PreviewData.blockIndex(sectionIndex, 2, 1, 3)]);
        assertEquals(Blocks.AIR.defaultBlockState(),
                target.blocks()[PreviewData.blockIndex(sectionIndex, 0, 0, 0)]);
        assertEquals(1, target.blockEntities().size());
        assertEquals("minecraft:chest", target.blockEntities().get(new BlockPos(2, 64, 3)).getString("id"));

        BlockState[] beforeBlocks = PreviewData.allocateBlocks(height);
        Arrays.fill(beforeBlocks, Blocks.AIR.defaultBlockState());
        beforeBlocks[PreviewData.blockIndex(sectionIndex, 1, 0, 2)] = Blocks.DIRT.defaultBlockState();
        beforeBlocks[PreviewData.blockIndex(sectionIndex, 3, 0, 3)] = Blocks.COBBLESTONE.defaultBlockState();
        PreviewData.State before = new PreviewData.State(beforeBlocks, Map.of());

        assertEquals(new PreviewDifference.Counts(1, 1, 1), PreviewData.blockCounts(before, target));
    }

    @Test
    void decodeRejectsInvalidChunkMetadataSectionsAndBlockEntities() {
        LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        ChunkPos pos = new ChunkPos(0, 0);

        CompoundTag wrongCoord = validChunkTag();
        wrongCoord.putInt("xPos", 1);

        CompoundTag wrongDataVersion = validChunkTag();
        wrongDataVersion.putInt("DataVersion", 1);

        CompoundTag wrongStatus = validChunkTag();
        wrongStatus.putString("Status", "minecraft:features");

        CompoundTag missingSectionY = validChunkTag();
        missingSectionY.getList("sections", 10).getCompound(0).remove("Y");

        CompoundTag duplicateSectionY = validChunkTag();
        CompoundTag secondSection = duplicateSectionY.getList("sections", 10).getCompound(0).copy();
        duplicateSectionY.getList("sections", 10).add(secondSection);

        CompoundTag outOfHeightSection = validChunkTag();
        outOfHeightSection.getList("sections", 10).getCompound(0).putByte("Y", (byte) 50);

        CompoundTag unregisteredBlock = validChunkTag();
        unregisteredBlock.getList("sections", 10).getCompound(0)
                .getCompound("block_states").getList("palette", 10).getCompound(0)
                .putString("Name", "minecraft:not_a_block");

        CompoundTag invalidProperty = validChunkTag();
        CompoundTag firstPalette = invalidProperty.getList("sections", 10).getCompound(0)
                .getCompound("block_states").getList("palette", 10).getCompound(0);
        CompoundTag props = new CompoundTag();
        props.putString("axis", "invalid");
        firstPalette.put("Properties", props);

        CompoundTag beOutsideChunk = validChunkTag();
        beOutsideChunk.put("block_entities", beList(beTag(16, 64, 0)));

        CompoundTag beOutsideHeight = validChunkTag();
        beOutsideHeight.put("block_entities", beList(beTag(2, 500, 3)));

        CompoundTag beDuplicatePos = validChunkTag();
        beDuplicatePos.put("block_entities", beList(beTag(2, 64, 3), beTag(2, 64, 3)));

        for (CompoundTag invalid : List.of(
                wrongCoord,
                wrongDataVersion,
                wrongStatus,
                missingSectionY,
                duplicateSectionY,
                outOfHeightSection,
                unregisteredBlock,
                invalidProperty,
                beOutsideChunk,
                beOutsideHeight,
                beDuplicatePos)) {
            var raw = new PreviewData.Raw(
                    1000L,
                    "S-20260910_120000",
                    invalid,
                    new PreviewData.EntityData(Map.of(), ChunkRollbackSupport.EntitySnapshotState.PRESENT, null));
            assertThrows(IOException.class, () -> PreviewData.decode(raw, height, pos));
        }
    }

    private static CompoundTag validChunkTag() {
        var container = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        container.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        return validChunkTag(container);
    }

    private static CompoundTag validChunkTag(PalettedContainer<BlockState> container) {
        Codec<PalettedContainer<BlockState>> codec = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
        CompoundTag blockStates = (CompoundTag) codec.encodeStart(NbtOps.INSTANCE, container).getOrThrow();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.put("block_states", blockStates);
        ListTag sections = new ListTag();
        sections.add(section);

        CompoundTag chunkTag = new CompoundTag();
        chunkTag.putInt("xPos", 0);
        chunkTag.putInt("zPos", 0);
        chunkTag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        chunkTag.putString("Status", "minecraft:full");
        chunkTag.put("sections", sections);
        return chunkTag;
    }

    private static CompoundTag beTag(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static ListTag beList(CompoundTag... tags) {
        ListTag list = new ListTag();
        list.addAll(Arrays.asList(tags));
        return list;
    }

    private PreviewSnapshotReader.Snapshot readSnapshot(byte[] entities) throws Exception {
        Path world = temp.resolve("world");
        // 用一个空复合标签即可测试读取路径，无需依赖游戏注册表解码。
        writeRegion(world.resolve("region/r.0.0.mca"), new byte[] { 10, 0, 0, 0 });
        if (entities != null)
            writeRegion(world.resolve("entities/r.0.0.mca"), entities);
        var store = new SnapshotStore(temp.resolve("store"));
        var blobs = new BlobStore(temp.resolve("store"), 6);
        var scan = new RegionProcessor(store::getIndex, blobs, 6).scanWorld(world, null, null);
        assertTrue(scan.getErrors().isEmpty(), scan.getErrors().toString());
        var manifest = new SnapshotManifest("S-20260910_120000", 1789012800L, "world");
        manifest.setRegions(scan.getRegions());
        manifest.setExtraFiles(scan.getFiles());
        store.saveSnapshot(manifest);
        var raw = PreviewSnapshotReader.read(store, blobs, 0, 0, null, "");
        assertEquals(manifest.getSnapshotId(), raw.snapshotId());
        return raw;
    }

    private static void writeRegion(Path path, byte[] bytes) throws IOException {
        Region region = new Region(0, 0, 1000);
        region.setChunk(0, new ChunkData(bytes, 0, 0));
        io.github.truscdo.foldbackup.core.format.AnvilTestSupport.writeRegionAnvil(path, region, 6);
    }
}
