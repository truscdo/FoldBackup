package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Checks output path and Region coordinate conflicts before restoring a world. */
final class WorldRestoreTargets {
    private final Set<String> paths = new HashSet<>();
    private final Set<String> regions = new HashSet<>();

    void addRegion(String path, int rx, int rz, String duplicatePathMessage) throws IOException {
        String key = path.toLowerCase(Locale.ROOT);
        if (!paths.add(key)) throw new IOException(duplicatePathMessage + path);
        int slash = key.lastIndexOf('/');
        if (!regions.add(key.substring(0, slash + 1) + rx + "," + rz))
            throw new IOException("Duplicate region coordinates: " + path);
    }

    void addFile(String path) throws IOException {
        if (!paths.add(path.toLowerCase(Locale.ROOT)))
            throw new IOException("Conflicting output path: " + path);
    }

    void validateParents() throws IOException {
        for (String path : paths) {
            int slash = path.lastIndexOf('/');
            while (slash >= 0) {
                if (paths.contains(path.substring(0, slash)))
                    throw new IOException("File/directory conflict: " + path);
                slash = path.lastIndexOf('/', slash - 1);
            }
        }
    }
}
