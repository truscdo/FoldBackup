package io.github.truscdo.foldbackup.core.restore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/** Outcome of the check phase: what to write, what to skip and what may be deleted. */
public final class RestorePlan {
    private final List<RestoreEntry> regions;
    private final List<RestoreEntry> files;
    private final int skippedRegions;
    private final int skippedFiles;
    private final SortedSet<String> deletions;
    private final List<String> removedDirectories;
    private final RestoreStats stats;

    RestorePlan(List<RestoreEntry> regions, List<RestoreEntry> files, int skippedRegions, int skippedFiles,
            SortedSet<String> deletions, List<String> emptiedDirectories) {
        this.regions = List.copyOf(regions);
        this.files = List.copyOf(files);
        this.skippedRegions = skippedRegions;
        this.skippedFiles = skippedFiles;
        this.deletions = Collections.unmodifiableSortedSet(deletions);
        this.removedDirectories = List.copyOf(emptiedDirectories);
        this.stats = new RestoreStats(this.regions.size(), this.files.size(), skippedRegions, skippedFiles,
                deletions.size(), suffixCounts(deletions), countWithoutSuffix(deletions),
                topmost(emptiedDirectories));
    }

    /** Keeps the outermost directory of each emptied subtree; nested ones follow with it. */
    private static List<String> topmost(List<String> directories) {
        Set<String> all = new HashSet<>(directories);
        List<String> top = new ArrayList<>();
        for (String directory : directories) {
            String parent = parentOf(directory);
            while (parent != null && !parent.isEmpty() && !all.contains(parent)) parent = parentOf(parent);
            if (parent == null || parent.isEmpty()) top.add(directory);
        }
        return top;
    }

    private static String parentOf(String directory) {
        if (directory.isEmpty()) return null;
        int slash = directory.lastIndexOf('/');
        return slash < 0 ? "" : directory.substring(0, slash);
    }

    private static Map<String, Integer> suffixCounts(Set<String> paths) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : paths) {
            String suffix = RestoreStats.suffixOf(path);
            if (suffix.isEmpty()) continue;
            counts.merge(suffix, 1, Integer::sum);
        }
        return counts;
    }

    private static int countWithoutSuffix(Set<String> paths) {
        int count = 0;
        for (String path : paths) if (RestoreStats.suffixOf(path).isEmpty()) count++;
        return count;
    }

    public List<RestoreEntry> regions() {
        return regions;
    }

    public List<RestoreEntry> files() {
        return files;
    }

    public int skippedRegions() {
        return skippedRegions;
    }

    public int skippedFiles() {
        return skippedFiles;
    }

    /** Every target path that may be removed; region units include their {@code .mcc} files. */
    public SortedSet<String> deletions() {
        return deletions;
    }

    /** Directories that hold nothing after the deletions, deepest first; the delete phase removes them. */
    public List<String> removedDirectories() {
        return removedDirectories;
    }

    public RestoreStats stats() {
        return stats;
    }

    /** Region entries grouped by their directory, preserving the source order inside each group. */
    public Map<String, List<RestoreEntry>> regionsByDirectory() {
        Map<String, List<RestoreEntry>> grouped = new LinkedHashMap<>();
        for (RestoreEntry entry : regions) {
            String path = entry.path();
            int slash = path.lastIndexOf('/');
            String directory = slash < 0 ? "" : path.substring(0, slash);
            grouped.computeIfAbsent(directory, key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }
}
