package io.github.truscdo.foldbackup.core;

import java.nio.file.Files;
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
        Path customRoot = findCustomDimensionsDir(directory);
        if (customRoot != null) {
            return customRoot.getParent().relativize(directory).toString().replace('\\', '/') + "/";
        }
        return directory.getFileName() == null
                ? OVERWORLD_SUBPATH
                : dimensionSubPath(directory.getFileName().toString());
    }

    public static Path worldRoot(Path dimensionDirectory) {
        Path directory = dimensionDirectory.toAbsolutePath().normalize();
        Path customRoot = findCustomDimensionsDir(directory);
        if (customRoot != null) {
            return customRoot.getParent();
        }
        if (directory.getFileName() != null
                && BUILTIN_DIMENSION_DIRS.contains(directory.getFileName().toString())
                && directory.getParent() != null) {
            return directory.getParent();
        }
        return directory;
    }

    /**
     * Locates the enclosing {@code <worldRoot>/dimensions} directory for a custom dimension path
     * ({@code <worldRoot>/dimensions/<namespace>/<path...>}), stopping if an enclosing world root
     * marker ({@code level.dat} or {@code session.lock}) is reached first.
     */
    private static Path findCustomDimensionsDir(Path directory) {
        if (isWorldRootMarkerPresent(directory)) {
            return null;
        }
        for (Path p = directory.getParent(); p != null; p = p.getParent()) {
            if (isWorldRootMarkerPresent(p)) {
                return null;
            }
            if (p.getFileName() != null
                    && p.getFileName().toString().equals(CUSTOM_DIMENSIONS_DIR)
                    && p.getParent() != null
                    && p.relativize(directory).getNameCount() >= 2) {
                return p;
            }
        }
        return null;
    }

    private static boolean isWorldRootMarkerPresent(Path dir) {
        return Files.exists(dir.resolve("level.dat")) || Files.exists(dir.resolve("session.lock"));
    }
}
