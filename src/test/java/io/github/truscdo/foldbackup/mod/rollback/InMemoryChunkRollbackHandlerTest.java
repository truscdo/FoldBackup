package io.github.truscdo.foldbackup.mod.rollback;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryChunkRollbackHandlerTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 32})
    void copiedStatesCanGrowWithoutChangingSource(int paletteSize) {
        BlockState initial = Block.BLOCK_STATE_REGISTRY.byId(0);
        var source = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, initial,
                PalettedContainer.Strategy.SECTION_STATES);
        for (int i = 0; i < paletteSize; i++) {
            source.set(i & 15, 0, i >> 4, Block.BLOCK_STATE_REGISTRY.byId(i));
        }

        var copy = InMemoryChunkRollbackHandler.copyWritableStates(source);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertSame(source.get(x, y, z), copy.get(x, y, z));
                }
            }
        }

        BlockState added = Block.BLOCK_STATE_REGISTRY.byId(paletteSize);
        copy.set(15, 15, 15, added);
        assertSame(added, copy.get(15, 15, 15));
        assertSame(initial, source.get(15, 15, 15));

        // 源容器仍能独立扩容，不影响已交给活动区块的副本。
        source.set(15, 15, 15, Block.BLOCK_STATE_REGISTRY.byId(paletteSize + 1));
        assertSame(added, copy.get(15, 15, 15));
    }
}
