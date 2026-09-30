package io.github.truscdo.foldbackup.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds the Minecraft save's session lock throughout an offline operation. */
final class WorldSaveLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorldSaveLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static WorldSaveLock acquire(Path dimensionDirectory) throws IOException {
        return acquireRoot(worldRoot(dimensionDirectory));
    }

    static WorldSaveLock acquireRoot(Path worldRoot) throws IOException {
        if (!Files.isDirectory(worldRoot)) throw new IOException("World directory not found: " + worldRoot);
        Path lockPath = worldRoot.resolve("session.lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("World is locked: " + lockPath);
            return new WorldSaveLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException("World is locked: " + lockPath, e);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static Path worldRoot(Path dimensionDirectory) {
        Path directory = dimensionDirectory.toAbsolutePath().normalize();
        for (Path current = directory; current != null; current = current.getParent()) {
            Path name = current.getFileName();
            if (name == null) continue;
            String value = name.toString();
            if ("dimensions".equals(value)) return current.getParent();
            if ("DIM-1".equals(value) || "DIM1".equals(value)) return current.getParent();
        }
        return directory;
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
