package io.github.truscdo.foldbackup.core;

import java.nio.file.Path;
import java.util.Set;

/** Shared snapshot dimension sub-path prefixes and world-root resolution rules. */
public final class WorldPaths {
    public static final String OVERWORLD_SUBPATH = "";
    public static final String NETHER_DIR = "DIM-1";
    public static final String END_DIR = "DIM1";
    public static final String NETHER_SUBPATH = NETHER_DIR + "/";
    public static final String END_SUBPATH = END_DIR + "/";
    public static final String CUSTOM_DIMENSIONS_DIR = "dimensions";

    private static final Set<String> BUILTIN_DIMENSION_DIRS = Set.of(NETHER_DIR, END_DIR);

    private WorldPaths() {}

    public static String dimensionSubPath(String worldDirName) {
        return switch (worldDirName) {
            case NETHER_DIR -> NETHER_SUBPATH;
            case END_DIR -> END_SUBPATH;
            default -> OVERWORLD_SUBPATH;
        };
    }

    public static String customDimensionSubPath(String namespace, String path) {
        return CUSTOM_DIMENSIONS_DIR + "/" + namespace + "/" + path + "/";
    }

    public static String dimensionSubPath(Path dimensionDirectory) {
        Path directory = dimensionDirectory.toAbsolutePath().normalize();
        for (Path parent = directory; parent != null; parent = parent.getParent()) {
            if (parent.getFileName() != null && parent.getFileName().toString().equals(CUSTOM_DIMENSIONS_DIR)) {
                return parent.getParent().relativize(directory).toString().replace('\\', '/') + "/";
            }
        }
        return directory.getFileName() == null ? OVERWORLD_SUBPATH : dimensionSubPath(directory.getFileName().toString());
    }

    public static Path worldRoot(Path dimensionDirectory) {
        Path root = dimensionDirectory.toAbsolutePath().normalize();
        if (root.getFileName() != null && BUILTIN_DIMENSION_DIRS.contains(root.getFileName().toString())) {
            root = root.getParent();
        }
        for (Path p = dimensionDirectory.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals(CUSTOM_DIMENSIONS_DIR)) {
                return p.getParent();
            }
        }
        return root;
    }
}
