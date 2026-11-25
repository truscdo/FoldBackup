package io.github.truscdo.foldbackup.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PreviewCommand} 的 Brigadier 路由、Tab 补全与非玩家校验测试。
 */
class PreviewCommandTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parsesPreviewRoutesAndRejectsLegacyAndDimensionSyntax() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260901_100000", 1000L, "world"));
        TestFoldBackupMod mod = new TestFoldBackupMod(store);

        record Invocation(boolean exit, Integer x, Integer z, String snapshotId) {}
        Invocation[] last = {null};
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PreviewCommand.register(dispatcher, mod,
                (ctx, x, z, snapshotId) -> {
                    last[0] = new Invocation(false, x, z, snapshotId);
                    return 1;
                },
                ctx -> {
                    last[0] = new Invocation(true, null, null, null);
                    return 1;
                });

        CommandSourceStack source = nonPlayerSource();

        assertEquals(1, dispatcher.execute("foldbackup preview exit", source));
        assertEquals(new Invocation(true, null, null, null), last[0]);

        assertEquals(1, dispatcher.execute("foldbackup preview from S-20260901_100000", source));
        assertEquals(new Invocation(false, null, null, "S-20260901_100000"), last[0]);

        assertEquals(1, dispatcher.execute("foldbackup preview from S-20260901_100000 at 10 -2", source));
        assertEquals(new Invocation(false, 10, -2, "S-20260901_100000"), last[0]);

        for (String invalid : List.of(
                "foldbackup preview",
                "foldbackup preview 10 -2",
                "foldbackup preview 10 -2 S-20260901_100000",
                "foldbackup preview from S-20260901_100000 10 -2",
                "foldbackup preview from S-20260901_100000 in minecraft:overworld at 10 -2")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(invalid, source), invalid);
        }
    }

    @Test
    void suggestsExitFromSnapshotsAndAtOnly() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260901_100000", 1000L, "world"));
        TestFoldBackupMod mod = new TestFoldBackupMod(store);

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PreviewCommand.register(dispatcher, mod, (ctx, x, z, snapshotId) -> 1, ctx -> 1);
        CommandSourceStack source = nonPlayerSource();

        List<String> rootSuggestions = suggest(dispatcher, source, "foldbackup preview ");
        assertTrue(rootSuggestions.contains("exit"), rootSuggestions.toString());
        assertTrue(rootSuggestions.contains("from"), rootSuggestions.toString());
        assertFalse(rootSuggestions.contains("S-20260901_100000"), rootSuggestions.toString());

        List<String> snapshotSuggestions = suggest(dispatcher, source, "foldbackup preview from S-2026");
        assertEquals(List.of("S-20260901_100000"), snapshotSuggestions);

        List<String> targetSuggestions = suggest(dispatcher, source,
                "foldbackup preview from S-20260901_100000 ");
        assertEquals(List.of("at"), targetSuggestions);
    }

    @Test
    void defaultRegistrationRejectsNonPlayerSources() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260901_100000", 1000L, "world"));
        TestFoldBackupMod mod = new TestFoldBackupMod(store);

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        PreviewCommand.register(dispatcher, mod);
        CommandSourceStack source = nonPlayerSource();

        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("foldbackup preview from S-20260901_100000", source));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("foldbackup preview from S-20260901_100000 at 0 0", source));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("foldbackup preview exit", source));
    }

    private static CommandSourceStack nonPlayerSource() {
        return new CommandSourceStack(
                CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 2,
                "test", Component.literal("test"), null, null);
    }

    private static List<String> suggest(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandSourceStack source, String input) throws Exception {
        var parse = dispatcher.parse(input, source);
        return dispatcher.getCompletionSuggestions(parse).get().getList().stream()
                .map(Suggestion::getText)
                .toList();
    }

    private static final class TestFoldBackupMod extends FoldBackupMod {
        private final SnapshotStore store;

        TestFoldBackupMod(SnapshotStore store) {
            super(new FoldBackupConfig() {
                @Override
                public String getDefaultLanguage() {
                    return "en_us";
                }

                @Override
                public boolean isAutoDetectPlayerLanguage() {
                    return false;
                }
            });
            this.store = store;
        }

        @Override
        public SnapshotStore getSnapshotStore() {
            return store;
        }
    }
}
