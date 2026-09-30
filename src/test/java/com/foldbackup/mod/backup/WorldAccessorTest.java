package com.foldbackup.mod.backup;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldAccessorTest {
    static final class Level implements WorldAccessor.SavingLevel {
        boolean disabled;
        Level(boolean disabled) { this.disabled = disabled; }
        public boolean noSave() { return disabled; }
        public void noSave(boolean value) { disabled = value; }
    }

    static class World extends WorldAccessor {
        final Level enabled = new Level(false);
        final Level disabled = new Level(true);
        boolean failSave;
        boolean failAfterSuspend;

        World() { super(null); }
        @Override Iterable<SavingLevel> savingLevels() { return List.of(enabled, disabled); }
        @Override public void saveAll(boolean flush) {
            assertFalse(enabled.disabled, "Save must precede suspension");
            if (failSave) throw new IllegalStateException("save failed");
        }
        @Override public void disableSaving() {
            super.disableSaving();
            if (failAfterSuspend) throw new IllegalStateException("suspension failed");
        }
        void assertRestored() {
            assertFalse(enabled.disabled);
            assertTrue(disabled.disabled);
        }
    }

    @Test void restoresOriginalStatesAndRepeatedRestoreIsHarmless() {
        World world = new World();
        world.enableSaving();
        world.assertRestored();
        world.disableSaving();
        assertTrue(world.enabled.disabled);
        assertTrue(world.disabled.disabled);
        world.enableSaving();
        world.assertRestored();
        world.enabled.disabled = true;
        world.enableSaving();
        assertTrue(world.enabled.disabled, "A completed suspension must not restore stale state");
    }

    @Test void repeatedSuspendCannotOverwriteOriginalState() {
        World world = new World();
        world.disableSaving();
        assertThrows(IllegalStateException.class, world::disableSaving);
        world.enableSaving();
        world.assertRestored();
    }
}
