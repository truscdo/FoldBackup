package io.github.truscdo.foldbackup.core.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link RepositoryLayout} 的路径规则（无 Minecraft 依赖）。 */
class RepositoryLayoutTest {

    private static final Path BASE = Path.of("C:/game/foldbackups");
    /** 与 {@code server.getWorldPath(LevelResource.ROOT)} 同形：存档目录下的 {@code .}。 */
    private static final Path WORLD_ROOT = Path.of("C:/game/saves/My World/.");

    @Test
    void dedicatedServerUsesBaseRootDirectly() {
        assertEquals(BASE, RepositoryLayout.repoRoot(BASE, WORLD_ROOT, true));
    }

    @Test
    void singleplayerNestsByWorldFolderName() {
        assertEquals(BASE.resolve("My World"), RepositoryLayout.repoRoot(BASE, WORLD_ROOT, false));
    }

    @Test
    void worldNameComesFromParentDirectory() {
        assertEquals("My World", RepositoryLayout.worldName(WORLD_ROOT));
    }

    @Test
    void worldNameFallsBackWhenParentIsMissing() {
        assertEquals(RepositoryLayout.FALLBACK_WORLD_NAME, RepositoryLayout.worldName(Path.of("world")));
        assertEquals(RepositoryLayout.FALLBACK_WORLD_NAME, RepositoryLayout.worldName(null));
    }

    @Test
    void singleplayerFallsBackToDefaultFolderName() {
        assertEquals(BASE.resolve(RepositoryLayout.FALLBACK_WORLD_NAME),
                RepositoryLayout.repoRoot(BASE, Path.of("world"), false));
    }

    @Test
    void repoRootRejectsMissingBaseRoot() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryLayout.repoRoot(null, WORLD_ROOT, false));
    }
}
