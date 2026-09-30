package io.github.truscdo.foldbackup.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the simplified CLI bootstrap support: nested-jar detection,
 * size-based completeness, idempotent export (mtime-stable second pass),
 * repair of partially damaged library dirs, and the usage/hint texts.
 */
class BootstrapSupportTest {

    @TempDir
    Path tmp;

    // ── helpers ────────────────────────────────────────────────

    /** Builds a synthetic "fat jar" with nested jars under META-INF/jarjar/. */
    private static void writeFatJar(Path out, List<String> nestedNames) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(out))) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (String nested : nestedNames) {
                zos.putNextEntry(new ZipEntry(nested));
                var inner = new java.io.ByteArrayOutputStream();
                try (var iz = new ZipOutputStream(inner)) {
                    iz.putNextEntry(new ZipEntry("placeholder.class"));
                    iz.write(new byte[] { 1, 2, 3 });
                    iz.closeEntry();
                }
                zos.write(inner.toByteArray());
                zos.closeEntry();
            }
        }
    }

    private static Map<Path, Long> snapshot(Path dir) throws IOException {
        Map<Path, Long> m = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".jar"))
                    .sorted().toList()) {
                m.put(p.getFileName(), Files.size(p));
                m.put(p.getFileName().resolveSibling("~mt." + p.getFileName()),
                        Files.getLastModifiedTime(p).toMillis());
            }
        }
        return m;
    }

    // ── detection (content-based) ──────────────────────────────

    @Test
    void detectsNestedJarsInSortedOrder() throws IOException {
        Path jar = tmp.resolve("fat.jar");
        writeFatJar(jar, List.of("META-INF/jarjar/zstd-jni-1.5.6-9.jar",
                "META-INF/jarjar/zero-allocation-hashing-2026.0.jar"));

        assertEquals(List.of(
                "META-INF/jarjar/zero-allocation-hashing-2026.0.jar",
                "META-INF/jarjar/zstd-jni-1.5.6-9.jar"),
                BootstrapSupport.listNestedJars(jar));
    }

    @Test
    void plainJarHasNoNestedJars() throws IOException {
        Path jar = tmp.resolve("plain.jar");
        writeFatJar(jar, List.of());
        assertTrue(BootstrapSupport.listNestedJars(jar).isEmpty(),
                "plain mod jar must route to direct run (no bootstrap)");
    }

    @Test
    void nestedSizesMatchZipEntries() throws IOException {
        Path jar = tmp.resolve("fat.jar");
        writeFatJar(jar, List.of("META-INF/jarjar/a-hash.jar", "META-INF/jarjar/z-zstd.jar"));

        Map<String, Long> sizes = BootstrapSupport.nestedJarSizes(jar,
                BootstrapSupport.listNestedJars(jar));
        assertTrue(sizes.containsKey("a-hash.jar") && sizes.containsKey("z-zstd.jar"));
        assertTrue(sizes.values().stream().allMatch(v -> v > 0));
    }

    // ── export: idempotent + self-repairing ────────────────────

    @Test
    void exportIsIdempotentAndMtimeStable() throws IOException {
        Path fat = tmp.resolve("self.jar");
        writeFatJar(fat, List.of("META-INF/jarjar/a-hash.jar", "META-INF/jarjar/z-zstd.jar"));
        List<String> nested = BootstrapSupport.listNestedJars(fat);
        Map<String, Long> expected = BootstrapSupport.nestedJarSizes(fat, nested);
        Path libDir = tmp.resolve(".foldbackup-cli");

        BootstrapSupport.exportMissing(fat, nested, libDir);
        assertTrue(BootstrapSupport.isComplete(libDir, expected), "complete after export");
        Map<Path, Long> firstPass = snapshot(libDir);

        BootstrapSupport.exportMissing(fat, nested, libDir);
        assertEquals(firstPass, snapshot(libDir),
                "second pass must not rewrite up-to-date files (zero-write hit)");
    }

    @Test
    void refusesToOverwriteDamagedLibraryInIncompleteDir() throws IOException {
        Path fat = tmp.resolve("self.jar");
        writeFatJar(fat, List.of("META-INF/jarjar/a-hash.jar", "META-INF/jarjar/z-zstd.jar"));
        List<String> nested = BootstrapSupport.listNestedJars(fat);
        Map<String, Long> expected = BootstrapSupport.nestedJarSizes(fat, nested);
        Path libDir = tmp.resolve(".foldbackup-cli");

        // partial dir: one truncated file, the other missing
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("a-hash.jar"), "truncated");

        assertFalse(BootstrapSupport.isComplete(libDir, expected),
                "partial/damaged dir is not trusted");

        IOException failure = assertThrows(IOException.class,
                () -> BootstrapSupport.exportMissing(fat, nested, libDir));
        assertTrue(failure.getMessage().contains("Refusing to overwrite existing runtime library"));
        assertFalse(BootstrapSupport.isComplete(libDir, expected), "damaged dir remains incomplete");
        assertEquals("truncated", Files.readString(libDir.resolve("a-hash.jar")),
                "existing damaged file is left untouched");
        assertFalse(Files.exists(libDir.resolve("z-zstd.jar")),
                "validation fails before exporting other missing libraries");
    }

    // ── message contracts ──────────────────────────────────────

    @Test
    void firstRunHintContainsOneTimeFixAndLibOption() {
        String hint = BootstrapSupport.firstRunHint();
        assertTrue(hint.contains("java -jar foldbackup.jar extract"), "one-time export command");
        assertFalse(hint.contains(".foldbackup-cli"), "no fixed library folder");
        assertTrue(hint.contains("--lib"), "manual --lib option");
        assertTrue(hint.endsWith("\n"), "ends with newline");
    }

    @Test
    void usageTextCoversSnapshotAndArchiveRoots() {
        String usage = BootstrapSupport.usageText();
        assertTrue(usage.contains("--snapshot-root <dir>"), "TUI form takes explicit online root");
        assertTrue(usage.contains("--archive-root <dir>"), "TUI form takes explicit archive root");
        assertTrue(usage.contains("extract"), "extract command documented");
        assertTrue(usage.contains("--lib"), "--lib option documented");
        assertTrue(usage.contains("world-export"), "world file output default documented");
        assertFalse(usage.contains("--format"), "online export no longer exposes LRH format selection");
    }

    // ── --lib parsing ──────────────────────────────────────────

    @Test
    void parseLibArgStripsLibFromAnyPosition() {
        var mid = BootstrapSupport.parseLibArg(
                new String[] { "<dir>", "list", "--lib", "/libs" });
        assertEquals(Path.of("/libs"), mid.libDir());
        assertArrayEquals(new String[] { "<dir>", "list" }, mid.rest());

        var head = BootstrapSupport.parseLibArg(
                new String[] { "--lib", "/libs", "<dir>", "list" });
        assertEquals(Path.of("/libs"), head.libDir());
        assertArrayEquals(new String[] { "<dir>", "list" }, head.rest());
    }

    @Test
    void parseLibArgWithoutLibKeepsArgs() {
        var none = BootstrapSupport.parseLibArg(new String[] { "<dir>", "list" });
        assertNull(none.libDir());
        assertArrayEquals(new String[] { "<dir>", "list" }, none.rest());
    }

    @Test
    void parseLibArgMissingValueThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BootstrapSupport.parseLibArg(new String[] { "<dir>", "--lib" }));
    }
}
