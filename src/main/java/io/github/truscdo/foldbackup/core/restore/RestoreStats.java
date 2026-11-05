package io.github.truscdo.foldbackup.core.restore;

import java.util.List;
import java.util.Map;

/**
 * Counts shown before the delete decision. The three groups are changed, skipped and deleted;
 * deleted entries are grouped by suffix, with the extension-less group kept separate.
 */
public record RestoreStats(int changedRegions, int changedFiles, int skippedRegions, int skippedFiles,
        int deletedFiles, Map<String, Integer> deletedBySuffix, int deletedWithoutSuffix,
        List<String> emptiedDirectories) {

    public RestoreStats {
        deletedBySuffix = Map.copyOf(deletedBySuffix);
        emptiedDirectories = List.copyOf(emptiedDirectories);
    }

    public int changed() {
        return changedRegions + changedFiles;
    }

    public int skipped() {
        return skippedRegions + skippedFiles;
    }

    /** Suffix used for grouping, including the dot; empty when the name has none. */
    public static String suffixOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot) : "";
    }
}
