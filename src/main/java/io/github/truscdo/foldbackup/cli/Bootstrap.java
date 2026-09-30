package io.github.truscdo.foldbackup.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Clean CLI entry point ({@code Main-Class}) — the ONLY class touched
 * before the business entry. This jar bundles its runtime libraries
 * (zstd-jni / zero-allocation-hashing) as NeoForge jar-in-jar nested jars
 * under {@code META-INF/jarjar/}; Java 9+ cannot append URLs to the system
 * (App) classloader, so the CLI cannot auto-load them at runtime.
 * <p>
 * Runtime libraries are kept in a reusable directory:
 * <ol>
 * <li>{@code java -jar foldbackup.jar extract [--output <library-dir>]} — export
 * the bundled libraries (default: {@code .foldbackup-cli});</li>
 * <li>other commands use that directory by default or accept {@code --lib}
 * to select another one; the business entry is
 * then relaunched on a fresh {@link java.net.URLClassLoader} whose parent
 * is the platform loader.</li>
 * </ol>
 * A jar without nested jars (plain mod jar / dev run) delegates directly.
 */
public final class Bootstrap {

    private static final String TARGET_CLASS = "io.github.truscdo.foldbackup.cli.BackupCli";

    public static void main(String[] args) {
        Path selfJar = locateSelfJar();
        if (selfJar == null) {
            // exploded/dev run: dependencies already on the classpath
            directRun(args);
            return;
        }
        List<String> nested;
        try {
            nested = BootstrapSupport.listNestedJars(selfJar);
        } catch (IOException e) {
            System.err.println("[bootstrap] cannot inspect own jar: " + e.getMessage());
            directRun(args);
            return;
        }
        if (nested.isEmpty()) {
            // plain mod jar: no bundled dependencies, nothing to export
            directRun(args);
            return;
        }

        // --lib <dir> 显式指定库目录（任意位置），剥离后传给业务入口
        BootstrapSupport.LibArgs libArgs;
        try {
            libArgs = BootstrapSupport.parseLibArg(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }
        String[] rest = libArgs.rest();

        if (rest.length == 0) {
            System.out.print(BootstrapSupport.usageText());
            System.exit(1);
        }
        if ("extract".equals(rest[0])) {
            Path output = Path.of(".foldbackup-cli");
            if (libArgs.libDir() != null) {
                System.err.println("Error: --lib does not apply to extract");
                System.exit(1);
                return;
            }
            if (rest.length == 3 && "--output".equals(rest[1]) && !rest[2].startsWith("--")) {
                output = Path.of(rest[2]);
            } else if (rest.length != 1) {
                System.err.println("Usage: java -jar foldbackup.jar extract [--output <library-dir>]");
                System.exit(1);
                return;
            }
            extractOnly(selfJar, nested, output.toString());
            return;
        }
        Path libDir = libArgs.libDir() == null ? Path.of(".foldbackup-cli") : libArgs.libDir();
        if (libArgs.libDir() == null) {
            try {
                Map<String, Long> expected = BootstrapSupport.nestedJarSizes(selfJar, nested);
                if (!BootstrapSupport.isComplete(libDir, expected)) {
                    System.err.println("Error: default runtime library directory is missing or incomplete: " + libDir);
                    System.err.print(BootstrapSupport.firstRunHint());
                    System.exit(1);
                    return;
                }
            } catch (IOException e) {
                System.err.println("Error: cannot inspect the default runtime library directory: " + e.getMessage());
                System.exit(1);
                return;
            }
        }
        relaunchWithLib(selfJar, libDir, rest);
    }

    /**
     * Manual export of the bundled libraries into the explicitly supplied directory.
     */
    private static void extractOnly(Path selfJar, List<String> nested, String outputArg) {
        Path libDir = Path.of(outputArg);
        try {
            Map<String, Long> expected = BootstrapSupport.nestedJarSizes(selfJar, nested);
            if (BootstrapSupport.isComplete(libDir, expected)) {
                info("bundled libraries already exported: " + libDir);
                return;
            }
            info("exporting bundled libraries to " + libDir + " ...");
            BootstrapSupport.exportMissing(selfJar, nested, libDir);
            info("done.");
        } catch (IOException e) {
            System.err.println("Error: cannot extract bundled libraries to " + libDir + ": " + e.getMessage());
            System.err.print(BootstrapSupport.firstRunHint());
            System.exit(1);
        }
    }

    /** Same-loader fallback when no bootstrap is needed. */
    private static void directRun(String[] args) {
        BackupCli.runBootstrapped(args);
    }

    /**
     * Relaunches the business entry on a fresh URLClassLoader over
     * [own jar, --lib dir jars] with the platform loader as parent (NOT the
     * App loader — that would resolve an old business-class copy without
     * dependency visibility).
     */
    private static void relaunchWithLib(Path selfJar, Path libDir, String[] args) {
        if (!Files.isDirectory(libDir)) {
            System.err.println("Error: --lib directory not found: " + libDir);
            System.err.print(BootstrapSupport.firstRunHint());
            System.exit(1);
        }
        List<Path> libJars;
        try {
            libJars = BootstrapSupport.listLibJars(libDir);
        } catch (IOException e) {
            System.err.println("Error: cannot read --lib directory: " + libDir);
            System.err.print(BootstrapSupport.firstRunHint());
            System.exit(1);
            return;
        }
        if (libJars.isEmpty()) {
            System.err.println("Error: no jars found in --lib directory: " + libDir);
            System.err.print(BootstrapSupport.firstRunHint());
            System.exit(1);
        }
        URL[] urls = Stream.concat(Stream.of(selfJar), libJars.stream())
                .map(Bootstrap::uncheckedToUrl)
                .toArray(URL[]::new);
        try (var loader = new java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> target = loader.loadClass(TARGET_CLASS);
            target.getMethod("runBootstrapped", String[].class).invoke(null, (Object) args);
        } catch (ReflectiveOperationException | IOException e) {
            System.err.println("Error: cannot start CLI with --lib: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static URL uncheckedToUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("bad url for " + p, e);
        }
    }

    /** Locates the running jar via CodeSource; null for exploded/dev runs. */
    static Path locateSelfJar() {
        try {
            var source = Bootstrap.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path p = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")
                    ? p
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void info(String msg) {
        PrintStream out = System.out;
        out.println("[bootstrap] " + msg);
    }

    private Bootstrap() {
    }
}
