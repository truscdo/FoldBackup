package io.github.truscdo.foldbackup.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import io.github.truscdo.foldbackup.core.rollback.RollbackScope;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.mod.FoldBackupMod;
import io.github.truscdo.foldbackup.mod.config.FoldBackupConfig;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * {@link ChunkRollbackCommand} 的 Brigadier 路由、范围解析、Tab 补全与多语言回档文案测试。
 */
class ChunkRollbackCommandTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rollbackTranslationsExistAcrossAllSupportedLanguages() {
        List<String> keys = List.of(
                "foldbackup.rollback.failure.entities_not_loaded",
                "foldbackup.rollback.failure.entities_unrecoverable",
                "foldbackup.rollback.success_blocks_only",
                "foldbackup.rollback.success_entities_only");
        for (String lang : List.of("en_us", "zh_cn", "es_es", "pt_br", "ru_ru")) {
            for (String key : keys) {
                String value = FoldBackupTranslations.textForLanguage(
                        lang, key, 10, -2, "S-20260902_120000", 1L, 2L, 0, 0);
                assertNotEquals(key, value, "Missing translation key " + key + " for language " + lang);
                assertFalse(value.isBlank(), "Blank translation key " + key + " for language " + lang);
                assertFalse(value.contains("%s") || value.contains("%1$s"),
                        "Unformatted placeholder in " + key + " for " + lang + ": " + value);
            }
        }
    }

    @Test
    void parsesDefaultBlocksAndEntitiesRoutesWithAndWithoutDimensionAndSnapshot() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260901_100000", 1000L, "world"));
        TestFoldBackupMod mod = new TestFoldBackupMod(store);

        record Invocation(RollbackScope scope, ResourceLocation dimension, int x, int z, String snapshotId) {}
        Invocation[] last = {null};
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ChunkRollbackCommand.register(dispatcher, mod, (ctx, explicitDim, x, z, snapshotId, scope) -> {
            ResourceLocation dim = explicitDim ? ctx.getArgument("dimension", ResourceLocation.class) : null;
            last[0] = new Invocation(scope, dim, x, z, snapshotId);
            return 1;
        });

        CommandSourceStack source = new CommandSourceStack(
                CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 2,
                "test", Component.literal("test"), null, null);

        assertEquals(1, dispatcher.execute("foldbackup rollback chunk 10 -2", source));
        assertEquals(new Invocation(RollbackScope.ALL, null, 10, -2, null), last[0]);

        assertEquals(1, dispatcher.execute("foldbackup rollback chunk -1 5 S-20260901_100000", source));
        assertEquals(new Invocation(RollbackScope.ALL, null, -1, 5, "S-20260901_100000"), last[0]);

        assertEquals(1, dispatcher.execute(
                "foldbackup rollback chunk in minecraft:the_nether 3 4 S-20260901_100000", source));
        assertEquals(new Invocation(
                RollbackScope.ALL, ResourceLocation.parse("minecraft:the_nether"), 3, 4, "S-20260901_100000"),
                last[0]);

        assertEquals(1, dispatcher.execute("foldbackup rollback chunk blocks 1 2", source));
        assertEquals(new Invocation(RollbackScope.BLOCKS, null, 1, 2, null), last[0]);

        assertEquals(1, dispatcher.execute(
                "foldbackup rollback chunk blocks in minecraft:overworld -4 8 S-20260901_100000", source));
        assertEquals(new Invocation(
                RollbackScope.BLOCKS, ResourceLocation.parse("minecraft:overworld"), -4, 8, "S-20260901_100000"),
                last[0]);

        assertEquals(1, dispatcher.execute("foldbackup rollback chunk entities 7 -9", source));
        assertEquals(new Invocation(RollbackScope.ENTITIES, null, 7, -9, null), last[0]);

        assertEquals(1, dispatcher.execute(
                "foldbackup rollback chunk entities in minecraft:the_end 0 0 S-20260901_100000", source));
        assertEquals(new Invocation(
                RollbackScope.ENTITIES, ResourceLocation.parse("minecraft:the_end"), 0, 0, "S-20260901_100000"),
                last[0]);
    }

    @Test
    void suggestsScopeLiteralsAndSnapshotIds() throws Exception {
        SnapshotStore store = new SnapshotStore(tempDir);
        store.saveSnapshot(new SnapshotManifest("S-20260901_100000", 1000L, "world"));
        TestFoldBackupMod mod = new TestFoldBackupMod(store);

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ChunkRollbackCommand.register(dispatcher, mod, (ctx, explicitDim, x, z, snapshotId, scope) -> 1);
        CommandSourceStack source = new CommandSourceStack(
                CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 2,
                "test", Component.literal("test"), null, null);

        List<String> chunkSuggestions = suggest(dispatcher, source, "foldbackup rollback chunk ");
        assertTrue(chunkSuggestions.contains("blocks"), chunkSuggestions.toString());
        assertTrue(chunkSuggestions.contains("entities"), chunkSuggestions.toString());
        assertTrue(chunkSuggestions.contains("in"), chunkSuggestions.toString());

        List<String> blocksSuggestions = suggest(dispatcher, source, "foldbackup rollback chunk blocks ");
        assertTrue(blocksSuggestions.contains("in"), blocksSuggestions.toString());

        List<String> snapshotSuggestions = suggest(dispatcher, source,
                "foldbackup rollback chunk entities 1 2 S-2026");
        assertEquals(List.of("S-20260901_100000"), snapshotSuggestions);
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
            super(new FoldBackupConfig() {});
            this.store = store;
        }

        @Override
        public SnapshotStore getSnapshotStore() {
            return store;
        }
    }
}
