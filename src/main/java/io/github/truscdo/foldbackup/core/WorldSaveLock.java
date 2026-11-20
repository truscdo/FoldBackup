package io.github.truscdo.foldbackup.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Holds the Minecraft save's session lock throughout an offline operation. */
public final class WorldSaveLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorldSaveLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WorldSaveLock acquireRoot(Path worldRoot) throws IOException {
        Objects.requireNonNull(worldRoot, "worldRoot");
        return acquireRoot(worldRoot, "World is locked: " + worldRoot.resolve("session.lock"));
    }

    public static WorldSaveLock acquireRoot(Path worldRoot, String lockedMessage) throws IOException {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Objects.requireNonNull(lockedMessage, "lockedMessage");
        if (!Files.isDirectory(worldRoot)) throw new IOException("World directory not found: " + worldRoot);
        Path lockPath = worldRoot.resolve("session.lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException(lockedMessage);
            return new WorldSaveLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeSuppressing(channel, e);
            throw new IOException(lockedMessage, e);
        } catch (IOException | RuntimeException e) {
            closeSuppressing(channel, e);
            throw e;
        }
    }

    private static void closeSuppressing(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
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
