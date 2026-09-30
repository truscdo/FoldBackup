package io.github.truscdo.foldbackup.cli;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.console.ArchiveVerifyReporter;
import io.github.truscdo.foldbackup.core.console.ProgressPrinter;
import io.github.truscdo.foldbackup.core.console.SnapshotTablePrinter;
import io.github.truscdo.foldbackup.core.archive.ArchiveDefaults;
import io.github.truscdo.foldbackup.core.archive.ArchiveVerifier;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackCoordinator;
import io.github.truscdo.foldbackup.core.rollback.ChunkRollbackSupport;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import io.github.truscdo.foldbackup.tui.BackupTui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Offline command-line entry point for snapshot and archive management. */
public class BackupCli {
    private static final Path WORKING_DIRECTORY = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) {
        runBootstrapped(args);
    }

    /** Business entry called by {@link Bootstrap} after runtime libraries are loaded. */
    public static void runBootstrapped(String[] args) {
        LogUtil.setPlatform(LogUtil.Platform.CLI);
        try {
            Invocation invocation = Invocation.parse(args);
            if (invocation.command().isEmpty()) {
                printUsage();
                return;
            }
            dispatch(invocation);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void dispatch(Invocation invocation) throws IOException {
        List<String> args = invocation.command();
        String top = args.getFirst();
        switch (top) {
            case "tui" -> {
                requireCount(args, 1, "tui");
                BackupTui.launch(invocation.snapshotRoot(), invocation.archiveRoot());
            }
            case "snapshot" -> dispatchSnapshot(invocation, args);
            case "archive" -> dispatchArchive(invocation, args);
            case "extract" -> throw new IOException("extract is available from the bundled foldbackup jar entry point");
            default -> throw new IOException("Unknown command: " + top + "\n\n" + BootstrapSupport.usageText());
        }
    }

    private static void dispatchSnapshot(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 2) throw new IOException("snapshot requires a subcommand");
        String subcommand = args.get(1);
        switch (subcommand) {
            case "list" -> {
                requireCount(args, 2, "snapshot list");
                SnapshotStore snapshots = snapshotStore(invocation.snapshotRoot());
                runList(new BackupExporter(snapshots,
                        new BlobStore(invocation.snapshotRoot(), ArchiveDefaults.EXPORT_LEVEL),
                        ArchiveDefaults.EXPORT_LEVEL));
            }
            case "create" -> runCreate(invocation.snapshotRoot(), args);
            case "import" -> runImport(invocation, args);
            case "export" -> runExport(invocation, args);
            case "delete" -> runDelete(invocation, args);
            case "rollback" -> runRollback(invocation, args);
            default -> throw new IOException("Unknown snapshot subcommand: " + subcommand);
        }
    }

    private static void dispatchArchive(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 2) throw new IOException("archive requires a subcommand");
        switch (args.get(1)) {
            case "create" -> ArchiveCli.runArchiveCreate(invocation.snapshotRoot(), invocation.archiveRoot(), args);
            case "export" -> ArchiveCli.runArchiveExport(invocation.archiveRoot(), args);
            case "verify" -> runVerify(invocation.archiveRoot(), args);
            default -> throw new IOException("Unknown archive subcommand: " + args.get(1));
        }
    }

    static void runCreate(Path root, List<String> args) throws IOException {
        Path world = null;
        Integer threads = null;
        for (int i = 2; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--world" -> {
                    if (world != null) throw new IOException("Duplicate --world");
                    world = Path.of(requireValue(args, ++i, "--world"));
                }
                case "--threads" -> {
                    if (threads != null) throw new IOException("Duplicate --threads");
                    String value = requireValue(args, ++i, "--threads");
                    try { threads = Integer.parseInt(value); }
                    catch (NumberFormatException e) { throw new IOException("--threads requires a positive integer", e); }
                    if (threads <= 0) throw new IOException("--threads requires a positive integer");
                }
                default -> throw new IOException("Unknown snapshot create option: " + args.get(i));
            }
        }
        if (world == null) throw new IOException("snapshot create requires --world <directory>");
        SnapshotManifest snapshot;
        try (var progress = new ProgressPrinter(System.err)) {
            snapshot = SnapshotCreation.create(world, root, threads == null ? 0 : threads, progress.onProgress());
        }
        System.out.println(SnapshotCreation.summary(snapshot, root));
    }

    private static void runImport(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 3 || !"--all-archive".equals(args.get(2)))
            throw new IOException("Usage: snapshot import --all-archive [--threads <N>]");
        int threads = parseThreads(args, 3);
        var importer = new io.github.truscdo.foldbackup.core.archive.ArchiveImporter(
                invocation.archiveRoot(), invocation.snapshotRoot(), threads);
        int imported;
        try (var progress = new ProgressPrinter(System.err)) {
            imported = importer.importAll(progress.onProgress());
        }
        System.out.println("Imported " + imported + " snapshot(s) into " + invocation.snapshotRoot());
    }

    private static void runExport(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 3) throw new IOException("Usage: snapshot export <snapshot-id> --output <path> [--zip] [--threads <N>]");
        String snapshotId = args.get(2);
        Path output = null;
        boolean zip = false;
        int threads = 0;
        boolean outputSeen = false;
        for (int i = 3; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--output" -> {
                    if (outputSeen) throw new IOException("Duplicate --output");
                    output = Path.of(requireValue(args, ++i, "--output"));
                    outputSeen = true;
                }
                case "--zip" -> {
                    if (zip) throw new IOException("Duplicate --zip");
                    zip = true;
                }
                case "--threads" -> {
                    if (threads != 0) throw new IOException("Duplicate --threads");
                    try { threads = Integer.parseInt(requireValue(args, ++i, "--threads")); }
                    catch (NumberFormatException e) { throw new IOException("--threads requires a positive integer", e); }
                    if (threads < 1) throw new IOException("--threads must be >= 1");
                }
                default -> throw new IOException("Unknown snapshot export option: " + args.get(i));
            }
        }
        if (output == null) throw new IOException("snapshot export requires --output <path>");
        SnapshotStore snapshots = snapshotStore(invocation.snapshotRoot());
        var exporter = new BackupExporter(snapshots,
                new BlobStore(invocation.snapshotRoot(), ArchiveDefaults.EXPORT_LEVEL), ArchiveDefaults.EXPORT_LEVEL);
        System.out.println("Exporting " + snapshotId + " to " + output + " ...");
        Path result;
        try (var progress = new ProgressPrinter(System.err)) {
            result = CliOutput.exportSnapshot(snapshots, exporter, snapshotId, output, zip, threads, progress.onProgress());
        }
        System.out.println("Done: " + result);
    }

    private static void runDelete(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 3) throw new IOException("Usage: snapshot delete <snapshot-id> [--yes]");
        String snapshotId = args.get(2);
        boolean yes = false;
        for (int i = 3; i < args.size(); i++) {
            if ("--yes".equals(args.get(i)) && !yes) yes = true;
            else throw new IOException("Unknown or duplicate snapshot delete option: " + args.get(i));
        }
        SnapshotStore snapshots = snapshotStore(invocation.snapshotRoot());
        long epoch = snapshots.resolveEpoch(snapshotId);
        SnapshotManifest snapshot = snapshots.loadSnapshot(epoch);
        System.out.println("Snapshot to delete: " + snapshotId);
        System.out.println("  Regions: " + (snapshot.getRegions() == null ? 0 : snapshot.getRegions().size()));
        System.out.println("  Files:   " + (snapshot.getExtraFiles() == null ? 0 : snapshot.getExtraFiles().size()));
        System.out.println("  Effect:  remove snapshot metadata and reclaim blobs no longer referenced by any snapshot");
        if (!confirm("Delete this snapshot?", yes)) {
            System.out.println("Delete cancelled.");
            return;
        }
        snapshots.deleteSnapshot(epoch);
        System.out.println("Snapshot deleted: " + snapshotId);
    }

    private static void runRollback(Invocation invocation, List<String> args) throws IOException {
        if (args.size() < 3) throw new IOException(
                "Usage: snapshot rollback <snapshot-id> --world <dir> --chunk <x> <z> [--yes]");
        String snapshotId = args.get(2);
        Path world = null;
        Integer chunkX = null;
        Integer chunkZ = null;
        boolean yes = false;
        boolean worldSeen = false;
        boolean chunkSeen = false;
        for (int i = 3; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--world" -> {
                    if (worldSeen) throw new IOException("Duplicate --world");
                    world = Path.of(requireValue(args, ++i, "--world"));
                    worldSeen = true;
                }
                case "--chunk" -> {
                    if (chunkSeen) throw new IOException("Duplicate --chunk");
                    chunkX = parseCoordinate(requireValue(args, ++i, "--chunk"));
                    chunkZ = parseCoordinate(requireValue(args, ++i, "--chunk"));
                    chunkSeen = true;
                }
                case "--yes" -> {
                    if (yes) throw new IOException("Duplicate --yes");
                    yes = true;
                }
                default -> throw new IOException("Unknown snapshot rollback option: " + args.get(i));
            }
        }
        if (world == null || chunkX == null || chunkZ == null)
            throw new IOException("snapshot rollback requires --world and --chunk <x> <z>");
        if (!Files.isDirectory(world)) throw new IOException("World dimension directory not found: " + world);

        SnapshotStore snapshots = snapshotStore(invocation.snapshotRoot());
        BlobStore blobs = new BlobStore(invocation.snapshotRoot(), ArchiveDefaults.EXPORT_LEVEL);
        long epoch = snapshots.resolveEpoch(snapshotId);
        String dimension = ChunkRollbackSupport.deriveDimensionSubPath(world);
        var requestedChunk = ChunkRollbackSupport.resolveChunk(
                snapshots, blobs, chunkX, chunkZ, epoch, dimension);
        if (requestedChunk.chunk() == null)
            throw new IOException("Requested chunk is empty in snapshot " + snapshotId);
        System.out.println("Rollback target:");
        System.out.println("  Snapshot:  " + snapshotId);
        System.out.println("  Dimension: " + (dimension.isEmpty() ? "overworld" : dimension));
        System.out.println("  Chunk:     [" + chunkX + ", " + chunkZ + "]");
        System.out.println("  Region:    r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        System.out.println("  Scope:     terrain, block entities, and recoverable entity data for this chunk");
        System.out.println("  Excludes:  POI, player data, and world-level configuration");
        if (!confirm("Apply this chunk rollback?", yes)) {
            System.out.println("Rollback cancelled.");
            return;
        }

        Path regionDir = world.resolve("region");
        if (!Files.isDirectory(regionDir)) throw new IOException("Region directory not found: " + regionDir);

        var coordinator = new ChunkRollbackCoordinator(snapshots, blobs, ArchiveDefaults.EXPORT_LEVEL);
        var result = coordinator.rollbackChunk(regionDir, chunkX, chunkZ, epoch, dimension);
        if (!result.success()) throw new IOException(result.message());
        System.out.println("Rollback complete: " + result.message());
    }

    private static void runVerify(Path archiveRoot, List<String> args) throws IOException {
        if (args.size() > 3) throw new IOException("Usage: archive verify [<archive-file>]");
        ArchiveVerifier.Result result;
        try (var progress = new ProgressPrinter(System.err)) {
            ArchiveVerifier verifier = new ArchiveVerifier();
            if (args.size() == 2) result = verifier.verifyAll(archiveRoot, progress.onProgress());
            else {
                Path archiveFile = resolveArchiveFile(archiveRoot, args.get(2));
                result = verifier.verifyArchive(archiveRoot, archiveFile, progress.onProgress());
            }
        }
        ArchiveVerifyReporter.print(result, System.out);
        if (!result.ok()) throw new IOException("Archive verification failed");
    }

    private static void runList(BackupExporter exporter) {
        List<BackupExporter.SnapshotInfo> snapshots = exporter.listSnapshotsWithCumulativeSpace();
        if (snapshots.isEmpty()) {
            System.out.println("No snapshots found.");
            return;
        }
        System.out.println("Snapshots (" + snapshots.size() + "):");
        SnapshotTablePrinter.printList(snapshots, System.out);
    }

    private static SnapshotStore snapshotStore(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Snapshot root not found: " + root);
        return new SnapshotStore(root);
    }

    private static int parseThreads(List<String> args, int start) throws IOException {
        int threads = 0;
        for (int i = start; i < args.size(); i++) {
            if (!"--threads".equals(args.get(i))) throw new IOException("Unknown option: " + args.get(i));
            if (threads != 0) throw new IOException("Duplicate --threads");
            try {
                threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
            } catch (NumberFormatException e) {
                throw new IOException("--threads requires a positive integer", e);
            }
            if (threads < 1) throw new IOException("--threads must be >= 1");
        }
        return threads;
    }

    private static int parseCoordinate(String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IOException("--chunk requires signed integer chunk coordinates", e);
        }
    }

    static Path resolveArchiveFile(Path archiveRoot, String argument) throws IOException {
        Path requested = Path.of(argument);
        if (requested.isAbsolute()) return requested.normalize();
        Path inArchiveRoot = archiveRoot.resolve(requested).normalize();
        if (Files.exists(inArchiveRoot)) return inArchiveRoot;
        return requested.toAbsolutePath().normalize();
    }

    private static boolean confirm(String prompt, boolean yes) throws IOException {
        if (yes) return true;
        java.io.Console console = System.console();
        if (console == null) throw new IOException("Confirmation requires an interactive terminal; pass --yes to confirm");
        String answer = console.readLine("%s [y/N] ", prompt);
        return answer != null && ("y".equalsIgnoreCase(answer.trim()) || "yes".equalsIgnoreCase(answer.trim()));
    }

    private static String requireValue(List<String> args, int index, String option) throws IOException {
        if (index >= args.size() || args.get(index).startsWith("--"))
            throw new IOException(option + " requires a value");
        return args.get(index);
    }

    private static void requireCount(List<String> args, int count, String usage) throws IOException {
        if (args.size() != count) throw new IOException("Usage: " + usage);
    }

    private static void printUsage() {
        System.out.print(BootstrapSupport.usageText());
    }

    private record Invocation(Path snapshotRoot, Path archiveRoot, List<String> command) {
        static Invocation parse(String[] raw) throws IOException {
            Path snapshotRoot = WORKING_DIRECTORY;
            Path archiveRoot = WORKING_DIRECTORY;
            boolean seenSnapshotRoot = false;
            boolean seenArchiveRoot = false;
            boolean seenLib = false;
            List<String> command = new ArrayList<>();
            for (int i = 0; i < raw.length; i++) {
                String arg = raw[i];
                switch (arg) {
                    case "--snapshot-root", "--online-root" -> {
                        if (seenSnapshotRoot) throw new IOException("Duplicate --snapshot-root");
                        snapshotRoot = Path.of(requireRawValue(raw, ++i, arg)).toAbsolutePath().normalize();
                        seenSnapshotRoot = true;
                    }
                    case "--archive-root" -> {
                        if (seenArchiveRoot) throw new IOException("Duplicate --archive-root");
                        archiveRoot = Path.of(requireRawValue(raw, ++i, arg)).toAbsolutePath().normalize();
                        seenArchiveRoot = true;
                    }
                    case "--tui" -> command.add("tui");
                    case "--lib" -> {
                        if (seenLib) throw new IOException("Duplicate --lib");
                        requireRawValue(raw, ++i, arg); // Bootstrap consumes this option in packaged runs.
                        seenLib = true;
                    }
                    default -> command.add(arg);
                }
            }
            return new Invocation(snapshotRoot, archiveRoot, List.copyOf(command));
        }

        private static String requireRawValue(String[] args, int index, String option) throws IOException {
            if (index >= args.length || args[index].startsWith("--"))
                throw new IOException(option + " requires a directory path");
            return args[index];
        }
    }
}
