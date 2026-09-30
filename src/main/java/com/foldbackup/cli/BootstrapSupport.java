package com.foldbackup.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Testable support logic for the CLI {@link Bootstrap}: nested-jar
 * enumeration, library-directory completeness checking and exporting.
 * Intentionally minimal — no fingerprints, markers or file locks. Existing
 * libraries are left in place; an incompatible same-name file is reported
 * instead of being overwritten.
 */
final class BootstrapSupport {

    private BootstrapSupport() {
    }

    /** 解析结果：--lib 指定的目录（可能为 null）+ 剥离后的参数。 */
    record LibArgs(Path libDir, String[] rest) {
    }

    /**
     * Extracts {@code --lib 
     * 
    <dir>
     * } from anywhere in the argument list.
     * Returns the lib directory (null if absent) and the remaining args
     * with the option and its value removed.
     */
    static LibArgs parseLibArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--lib".equals(args[i])) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--lib requires a directory path");
                }
                return new LibArgs(Path.of(args[i + 1]), removeRange(args, i, i + 2));
            }
        }
        return new LibArgs(null, args);
    }

    private static String[] removeRange(String[] args, int from, int to) {
        String[] rest = new String[args.length - (to - from)];
        System.arraycopy(args, 0, rest, 0, from);
        System.arraycopy(args, to, rest, from, args.length - to);
        return rest;
    }

    /**
     * Lists the entry names of nested jars under {@code META-INF/jarjar/}
     * inside the given jar, sorted. Empty ⇒ the jar carries no bundled
     * dependencies (plain mod jar / dev run) and no bootstrap is needed.
     */
    static List<String> listNestedJars(Path selfJar) throws IOException {
        List<String> result = new ArrayList<>();
        try (var zf = new java.util.zip.ZipFile(selfJar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                if (!e.isDirectory() && e.getName().startsWith("META-INF/jarjar/")
                        && e.getName().endsWith(".jar")) {
                    result.add(e.getName());
                }
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /** Expected bundled-lib file name → size, derived from the jar entries. */
    static Map<String, Long> nestedJarSizes(Path selfJar, List<String> nestedNames)
            throws IOException {
        Map<String, Long> sizes = new LinkedHashMap<>();
        try (var zf = new java.util.zip.ZipFile(selfJar.toFile())) {
            for (String name : nestedNames) {
                var entry = zf.getEntry(name);
                if (entry == null) {
                    throw new IOException("nested jar missing: " + name);
                }
                sizes.put(name.substring(name.lastIndexOf('/') + 1), entry.getSize());
            }
        }
        return sizes;
    }

    /**
     * Complete iff every expected lib jar exists with the exact expected
     * size — cheap validity check without marker files or fingerprints.
     */
    static boolean isComplete(Path libDir, Map<String, Long> expected) throws IOException {
        if (!Files.isDirectory(libDir)) {
            return false;
        }
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            Path f = libDir.resolve(e.getKey());
            if (!Files.isRegularFile(f) || Files.size(f) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies nested jars that are missing from {@code libDir}. A complete
     * directory is left untouched. A same-name file with the wrong size is
     * never overwritten.
     */
    static void exportMissing(Path selfJar, List<String> nestedNames, Path libDir)
            throws IOException {
        Files.createDirectories(libDir);
        Map<String, Long> expected = nestedJarSizes(selfJar, nestedNames);
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            Path out = libDir.resolve(e.getKey());
            if (Files.exists(out) && (!Files.isRegularFile(out) || Files.size(out) != e.getValue())) {
                throw new IOException("Refusing to overwrite existing runtime library: " + out);
            }
        }
        try (var zf = new java.util.zip.ZipFile(selfJar.toFile())) {
            for (String name : nestedNames) {
                var entry = zf.getEntry(name);
                Path out = libDir.resolve(name.substring(name.lastIndexOf('/') + 1));
                if (Files.exists(out)) {
                    continue; // up to date — do not touch
                }
                try (InputStream in = zf.getInputStream(entry);
                        var os = Files.newOutputStream(out,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE)) {
                    in.transferTo(os);
                }
            }
        }
    }

    /** Lists library jars inside a directory (for --lib / classpath building). */
    static List<Path> listLibJars(Path dir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            ds.forEach(jars::add);
        }
        jars.sort(Comparator.comparing(Path::getFileName));
        return jars;
    }

    /**
     * Short pointer shown whenever the CLI cannot proceed because the
     * bundled libraries have never been exported (or cannot be written):
     * what happened, the one-time fix, and the manual escape hatch.
     */
    static String firstRunHint() {
        return """
                Export the bundled libraries first:
                  java -jar foldbackup.jar extract
                Or point the CLI at them with --lib:
                  java -jar foldbackup.jar snapshot list --lib <library-dir>
                """;
    }

    /**
     * Canonical CLI usage text. Lives here so the pre-bootstrap entry
     * ({@link Bootstrap}) can answer any invocation without touching any
     * business class; mirrored by {@code BackupCli.printUsage()}.
     */
    static String usageText() {
        return """
                Usage:
                  java -jar foldbackup-<version>.jar extract [--output <dir>]
                  java -jar foldbackup-<version>.jar snapshot list
                  java -jar foldbackup-<version>.jar snapshot create --world <directory> [--threads <N>]
                  java -jar foldbackup-<version>.jar snapshot import --all-archive
                  java -jar foldbackup-<version>.jar snapshot export <snapshot-id> --output <path> [--zip] [--threads <N>]
                  java -jar foldbackup-<version>.jar snapshot delete <snapshot-id> [--yes]
                  java -jar foldbackup-<version>.jar snapshot rollback <snapshot-id> --world <dir> --chunk <x> <z> [--yes]
                  java -jar foldbackup-<version>.jar archive create --snapshot <snapshot-id> [--base <snapshot-id>]
                  java -jar foldbackup-<version>.jar archive export <archive-file> --output <dir> [--threads <N>]
                  java -jar foldbackup-<version>.jar archive verify [<archive-file>]
                  java -jar foldbackup-<version>.jar tui

                Defaults:
                  --snapshot-root <dir>  Working directory (snapshot commands, archive create, tui)
                  --archive-root <dir>   Working directory (archive commands, snapshot import, tui)
                  --lib <dir>            .foldbackup-cli (all commands except extract)
                  extract output:        .foldbackup-cli when --output is omitted
                  TUI export output:     world-export/<snapshot-id>

                Options:
                  --world <dir>        Closed world directory for snapshot create; dimension directory for rollback
                  --zip                 Package snapshot export as a zip archive (.zip path required)
                  --output <path>       Final world directory or zip file path
                  --level <N>           archive create compression level
                  --no-recompress       archive create full archive without recompressing
                  --threads <N>         archive create/export and snapshot create/export/import worker count
                  --yes                 Skip delete/rollback confirmation
                  --lib <path>          Directory with the bundled runtime libraries
                                        (zstd-jni, zero-allocation-hashing)

                TUI launcher example:
                  tui --snapshot-root <dir> --archive-root <dir>
                """;
    }
}
