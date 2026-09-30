package io.github.truscdo.foldbackup.mod.preview;

import io.github.truscdo.foldbackup.core.rollback.PreviewDifference.Counts;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreviewBlockEntityDifferenceTest {
    private static PreviewData.State state(CompoundTag tag) {
        return new PreviewData.State(new BlockState[0], Map.of(BlockPos.ZERO, tag));
    }

    @Test void ignoresStorageMarkerOnEitherSideWithoutChangingSourceTags() {
        CompoundTag current = new CompoundTag();
        current.putString("id", "minecraft:chest");
        for (boolean packed : new boolean[]{false, true}) {
            CompoundTag historical = current.copy();
            historical.putBoolean("keepPacked", packed);
            CompoundTag original = historical.copy();
            assertEquals(new Counts(0, 0, 0), PreviewData.blockEntityCounts(state(current), state(historical)));
            assertEquals(new Counts(0, 0, 0), PreviewData.blockEntityCounts(state(historical), state(current)));
            assertEquals(original, historical);
            assertFalse(current.contains("keepPacked"));
        }
    }

    @Test void stillReportsContentChangesAdditionsAndRemovals() {
        CompoundTag current = new CompoundTag();
        current.putString("CustomName", "before");
        CompoundTag historical = current.copy();
        historical.putString("CustomName", "after");
        historical.putBoolean("keepPacked", false);
        var empty = new PreviewData.State(new BlockState[0], Map.of());
        assertEquals(new Counts(0, 0, 1), PreviewData.blockEntityCounts(state(current), state(historical)));
        assertEquals(new Counts(1, 0, 0), PreviewData.blockEntityCounts(empty, state(historical)));
        assertEquals(new Counts(0, 1, 0), PreviewData.blockEntityCounts(state(current), empty));
    }
}
