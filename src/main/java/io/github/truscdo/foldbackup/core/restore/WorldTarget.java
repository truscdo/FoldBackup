package io.github.truscdo.foldbackup.core.restore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Target directory guard for offline restores.
 * <p>
 * Validates the requested output path, decides whether it is a new save or an existing one,
 * and holds the world lock from the check phase until deletion ends. Creating directories is
 * deferred to {@link #ensureDirectory()} so a check-only run leaves no trace.
 */
public final class WorldTarget implements AutoCloseable {
    private static final String LEVEL_DAT = "level.dat";

    private final Path root;
    private final WorldSaveLock lock;

    private WorldTarget(Path root, WorldSaveLock lock) {
        this.root = root;
        this.lock = lock;
    }

    public static WorldTarget open(Path requested) throws IOException {
        if (requested == null || requested.toString().isBlank())
            throw new IOException("Output path is required");
        Path root = requested.toAbsolutePath().normalize();
        if (root.getFileName() == null)
            throw new IOException("Output path must name a file or directory: " + requested);

        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Output path is not a directory: " + root);
            if (isEmpty(root)) return new WorldTarget(root, null);
            if (!Files.exists(root.resolve(LEVEL_DAT), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Not a Minecraft save (no " + LEVEL_DAT + "): " + root);
            WorldSaveLock lock;
            try {
                lock = WorldSaveLock.acquireRoot(root);
            } catch (IOException e) {
                throw new IOException("World is in use: " + root, e);
            }
            return new WorldTarget(root, lock);
        }

        requireCreatable(root);
        return new WorldTarget(root, null);
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            return !listing.iterator().hasNext();
        }
    }

    /** Fails when an existing ancestor is a file, so the write phase cannot create the tree. */
    private static void requireCreatable(Path root) throws IOException {
        for (Path parent = root.getParent(); parent != null; parent = parent.getParent()) {
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Output path cannot be created, parent is not a directory: " + parent);
            return;
        }
    }

    public Path directory() {
        return root;
    }

    /** Creates the directory tree; only the write phase calls this. */
    public void ensureDirectory() throws IOException {
        Files.createDirectories(root);
    }

    @Override
    public void close() throws IOException {
        if (lock != null) lock.close();
    }
}
