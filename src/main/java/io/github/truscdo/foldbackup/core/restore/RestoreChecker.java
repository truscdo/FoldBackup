package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.McaHasher;
import io.github.truscdo.foldbackup.core.format.McaStream;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the target directory with the restore list.
 * <p>
 * Entries whose recorded metadata still matches are skipped without reading content; everything
 * else is compared by content identity. Unreadable targets count as different so the write phase
 * can replace them. Nothing here writes, creates directories or touches the world lock.
 */
public final class RestoreChecker {
    private static final Pattern CHUNK_FILE = Pattern.compile("^c\\.(-?\\d+)\\.(-?\\d+)\\.mcc$");
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final String SESSION_LOCK = "session.lock";

    private RestoreChecker() {
    }

    public static RestorePlan check(WorldTarget target, RestoreSource source, int threads,
            ProgressListener listener) throws IOException {
        Path root = target.directory();
        List<RestoreEntry> entries = source.entries();
        Map<String, RestoreEntry> index = index(entries);

        List<Path> targetFiles = new ArrayList<>();
        List<Path> targetDirectories = new ArrayList<>();
        List<Path> otherEntries = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var walk = Files.walk(root)) {
                walk.filter(path -> !path.equals(root)).forEach(path -> {
                    if (Files.isRegularFile(path)) targetFiles.add(path);
                    else if (Files.isDirectory(path)) targetDirectories.add(path);
                    else otherEntries.add(path);
                });
            }
        }

        var progress = new ProgressTracker(listener);
        progress.stage("Checking", entries.size());
        boolean[] write = compareAll(root, entries, threads, progress);

        List<RestoreEntry> regions = new ArrayList<>();
        List<RestoreEntry> files = new ArrayList<>();
        int skippedRegions = 0;
        int skippedFiles = 0;
        for (int i = 0; i < entries.size(); i++) {
            RestoreEntry entry = entries.get(i);
            if (write[i]) {
                if (entry.isRegion()) regions.add(entry);
                else files.add(entry);
            } else if (entry.isRegion()) {
                skippedRegions++;
            } else {
                skippedFiles++;
            }
        }

        SortedSet<String> deletions = new TreeSet<>();
        classifyDeletions(root, targetFiles, index, deletions);
        List<String> emptied = emptiedDirectories(root, targetFiles, targetDirectories, otherEntries, deletions);
        return new RestorePlan(regions, files, skippedRegions, skippedFiles, deletions, emptied);
    }

    private static Map<String, RestoreEntry> index(List<RestoreEntry> entries) throws IOException {
        Map<String, RestoreEntry> index = new HashMap<>();
        for (RestoreEntry entry : entries) {
            String key = entry.path().toLowerCase(Locale.ROOT);
            if (index.put(key, entry) != null)
                throw new IOException("Duplicate restore path: " + entry.path());
        }
        return index;
    }

    private static boolean[] compareAll(Path root, List<RestoreEntry> entries, int threads,
            ProgressTracker progress) {
        boolean[] write = new boolean[entries.size()];
        int workers = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers));
        try {
            List<Future<Boolean>> results = new ArrayList<>(entries.size());
            for (RestoreEntry entry : entries) {
                Path file = root.resolve(entry.path());
                if (!Files.isRegularFile(file)) {
                    results.add(null);
                    continue;
                }
                results.add(pool.submit(() -> {
                    try {
                        return needsWrite(root, entry, file);
                    } finally {
                        // Report as soon as the entry is decided, not after the whole batch.
                        synchronized (progress) {
                            progress.done(entry.path());
                        }
                    }
                }));
            }
            for (int i = 0; i < entries.size(); i++) {
                Future<Boolean> result = results.get(i);
                if (result == null) {
                    write[i] = true;
                    synchronized (progress) {
                        progress.done(entries.get(i).path());
                    }
                    continue;
                }
                try {
                    write[i] = result.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while checking " + entries.get(i).path(), e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    write[i] = true;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return write;
    }

    private static boolean needsWrite(Path root, RestoreEntry entry, Path file) {
        try {
            if (metadataMatches(root, entry)) return false;
            String actual = entry.isRegion() ? regionHash(file) : McaHasher.inspectFile(file).hash();
            return !entry.contentHash().equals(actual);
        } catch (IOException e) {
            // Unreadable or truncated targets are replaced rather than reported.
            return true;
        }
    }

    /** Fast path: size and modification time of every file in the unit still match the record. */
    private static boolean metadataMatches(Path root, RestoreEntry entry) throws IOException {
        SourceMetadata recorded = entry.sourceMetadata();
        if (recorded.isEmpty()) return false;
        List<SourceMetadata.SourceFile> current = currentMetadata(root, entry);
        if (current.size() != recorded.files().size()) return false;
        Map<String, SourceMetadata.SourceFile> byPath = new HashMap<>();
        for (SourceMetadata.SourceFile file : current) byPath.put(file.relativePath(), file);
        for (SourceMetadata.SourceFile want : recorded.files()) {
            SourceMetadata.SourceFile got = byPath.get(want.relativePath());
            if (got == null || got.size() != want.size() || got.modifiedMillis() != want.modifiedMillis())
                return false;
        }
        return true;
    }

    private static List<SourceMetadata.SourceFile> currentMetadata(Path root, RestoreEntry entry)
            throws IOException {
        Path file = root.resolve(entry.path());
        if (!entry.isRegion())
            return List.of(SourceMetadata.SourceFile.of(entry.path(), attributes(file)));
        List<SourceMetadata.SourceFile> files = new ArrayList<>();
        files.add(SourceMetadata.SourceFile.of(entry.path(), attributes(file)));
        for (Path mcc : McaHasher.findMccFilesForRegion(file.getParent(), entry.regionX(), entry.regionZ()))
            files.add(SourceMetadata.SourceFile.of(relative(root, mcc), attributes(mcc)));
        return files;
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class);
    }

    private static String regionHash(Path mca) throws IOException {
        return HashUtil.bytesToHex(RegionStream.inspect(McaStream.source(mca)).hash());
    }

    private static void classifyDeletions(Path root, List<Path> targetFiles, Map<String, RestoreEntry> index,
            SortedSet<String> deletions) throws IOException {
        for (Path file : targetFiles) {
            String name = file.getFileName().toString();
            if (SESSION_LOCK.equals(name)) continue;
            String path = relative(root, file);
            if (index.containsKey(path.toLowerCase(Locale.ROOT))) continue;

            Matcher region = REGION_FILE.matcher(name);
            if (region.matches()) {
                addRegionUnit(file.getParent(), Integer.parseInt(region.group(1)), Integer.parseInt(region.group(2)),
                        root, deletions);
                continue;
            }
            Matcher chunk = CHUNK_FILE.matcher(name);
            if (chunk.matches()) {
                int regionX = Math.floorDiv(Integer.parseInt(chunk.group(1)), 32);
                int regionZ = Math.floorDiv(Integer.parseInt(chunk.group(2)), 32);
                Path unit = unitPath(file.getParent(), regionX, regionZ);
                if (!index.containsKey(relative(root, unit).toLowerCase(Locale.ROOT)))
                    addRegionUnit(file.getParent(), regionX, regionZ, root, deletions);
                continue;
            }
            deletions.add(path);
        }
    }

    private static void addRegionUnit(Path directory, int regionX, int regionZ, Path root,
            SortedSet<String> deletions) throws IOException {
        deletions.add(relative(root, unitPath(directory, regionX, regionZ)));
        for (Path mcc : McaHasher.findMccFilesForRegion(directory, regionX, regionZ))
            deletions.add(relative(root, mcc));
    }

    private static Path unitPath(Path directory, int regionX, int regionZ) {
        return directory.resolve("r." + regionX + "." + regionZ + ".mca");
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Every directory that holds nothing once the deletion candidates are gone, deepest first.
     * <p>
     * A directory counts when it has no surviving file and every child directory counts too, so
     * intermediate directories that only contained other directories are included. Anything that
     * is neither a regular file nor a directory keeps its parent from counting.
     */
    private static List<String> emptiedDirectories(Path root, List<Path> targetFiles,
            List<Path> targetDirectories, List<Path> otherEntries, Set<String> deletions) {
        Map<String, List<String>> filesByDirectory = new HashMap<>();
        for (Path file : targetFiles) {
            String path = relative(root, file);
            filesByDirectory.computeIfAbsent(parentOf(path), key -> new ArrayList<>()).add(path);
        }

        Set<String> blocked = new HashSet<>();
        for (Path other : otherEntries) blocked.add(parentOf(relative(root, other)));

        Map<String, List<String>> childrenByDirectory = new HashMap<>();
        List<String> directories = new ArrayList<>(targetDirectories.size());
        for (Path directory : targetDirectories) {
            String path = relative(root, directory);
            directories.add(path);
            childrenByDirectory.computeIfAbsent(parentOf(path), key -> new ArrayList<>()).add(path);
        }
        directories.sort((left, right) -> Integer.compare(depth(right), depth(left)));

        Map<String, Boolean> emptyAfter = new HashMap<>();
        for (String directory : directories) {
            boolean empty = !blocked.contains(directory);
            if (empty) {
                for (String file : filesByDirectory.getOrDefault(directory, List.of())) {
                    if (!deletions.contains(file)) {
                        empty = false;
                        break;
                    }
                }
            }
            if (empty) {
                for (String child : childrenByDirectory.getOrDefault(directory, List.of())) {
                    if (!Boolean.TRUE.equals(emptyAfter.get(child))) {
                        empty = false;
                        break;
                    }
                }
            }
            emptyAfter.put(directory, empty);
        }

        List<String> emptied = new ArrayList<>();
        for (String directory : directories) {
            if (Boolean.TRUE.equals(emptyAfter.get(directory))) emptied.add(directory);
        }
        return emptied;
    }

    private static int depth(String path) {
        if (path.isEmpty()) return 0;
        int depth = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
    }

    private static String parentOf(String directory) {
        if (directory.isEmpty()) return null;
        int slash = directory.lastIndexOf('/');
        return slash < 0 ? "" : directory.substring(0, slash);
    }
}
