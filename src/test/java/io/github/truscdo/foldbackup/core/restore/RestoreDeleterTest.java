package io.github.truscdo.foldbackup.core.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreDeleterTest {
    @TempDir Path temp;

    private Path world() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "world metadata");
        return world;
    }

    private static RestorePlan plan(Set<String> deletions, List<String> emptiedDirectories) {
        return new RestorePlan(List.of(), List.of(), 0, 0, new TreeSet<>(deletions), emptiedDirectories);
    }

    @Test
    void failedDeletionsAreAllReportedAndTheRestIsStillAttempted() throws IOException {
        Path world = world();
        for (String name : List.of("busy-a", "busy-b")) {
            Path directory = Files.createDirectories(world.resolve(name));
            Files.writeString(directory.resolve("keep.txt"), "keep");
        }
        Files.writeString(world.resolve("gone.txt"), "delete me");
        RestorePlan plan = plan(Set.of("busy-a", "busy-b", "gone.txt"), List.of());

        try (WorldTarget target = WorldTarget.open(world)) {
            IOException failure = assertThrows(IOException.class,
                    () -> RestoreDeleter.delete(target, plan, null));
            assertTrue(failure.getMessage().contains("2 path(s)"), failure.getMessage());
            assertTrue(failure.getMessage().contains("busy-a"), failure.getMessage());
            assertTrue(failure.getMessage().contains("busy-b"), failure.getMessage());
        }

        assertFalse(Files.exists(world.resolve("gone.txt")), "other candidates are still attempted");
        assertTrue(Files.exists(world.resolve("busy-a/keep.txt")), "failed candidates are left untouched");
    }

    @Test
    void emptiedDirectoriesAreRemovedFromDeepestToOutermost() throws IOException {
        Path world = world();
        Path deep = Files.createDirectories(world.resolve("cache/deep"));
        Files.writeString(deep.resolve("a.txt"), "a");
        Files.writeString(deep.resolve("b.txt"), "b");
        RestorePlan plan = plan(Set.of("cache/deep/a.txt", "cache/deep/b.txt"),
                List.of("cache/deep", "cache"));

        try (WorldTarget target = WorldTarget.open(world)) {
            RestoreDeleter.delete(target, plan, null);
        }

        assertFalse(Files.exists(world.resolve("cache")), "the fully emptied subtree is gone");
        assertTrue(Files.exists(world.resolve("level.dat")));
    }

    @Test
    void aDirectoryRefilledAfterTheCheckIsLeftInPlaceWithoutFailing() throws IOException {
        Path world = world();
        Path emptied = Files.createDirectories(world.resolve("emptied"));
        RestorePlan plan = plan(Set.of(), List.of("emptied"));
        Files.writeString(emptied.resolve("late.txt"), "appeared after the check");

        try (WorldTarget target = WorldTarget.open(world)) {
            RestoreDeleter.delete(target, plan, null);
        }

        assertEquals("appeared after the check", Files.readString(world.resolve("emptied/late.txt")));
    }
}
