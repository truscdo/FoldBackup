package io.github.truscdo.foldbackup.core;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Shared file replacement and Windows-tolerant recursive cleanup helpers.
 */
public final class FileIo {

    public enum DeletePolicy {
        /** Stops and throws on the first deletion failure. */
        FAIL_FAST,
        /** Attempts every entry and throws the primary failure with remaining errors suppressed. */
        COLLECT,
        /** Best-effort cleanup that ignores walk and deletion errors. */
        QUIET
    }

    private static final int DELETE_MAX_ATTEMPTS = 20;
    private static final long DELETE_RETRY_DELAY_MILLIS = 25L;

    private FileIo() {}

    /**
     * Moves {@code source} onto {@code destination}, preferring an atomic replace and falling back
     * to a standard replace when the filesystem rejects atomic moves.
     */
    public static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes a single path without throwing on failure. */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    /**
     * Deletes a single path with bounded retries for transient Windows directory-not-empty or
     * access-denied states, preserving the caller's interrupt flag.
     */
    public static void deleteWithRetry(Path path) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (DirectoryNotEmptyException | AccessDeniedException e) {
                    if (attempt == DELETE_MAX_ATTEMPTS - 1) throw e;
                    try {
                        Thread.sleep(DELETE_RETRY_DELAY_MILLIS);
                    } catch (InterruptedException cancellation) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Deletes {@code root} and all descendants deepest-first, returning early if absent. */
    public static void deleteTree(Path root) throws IOException {
        deleteTree(root, true, false, DeletePolicy.FAIL_FAST);
    }

    /**
     * Deletes {@code root} and all descendants deepest-first under the requested existence,
     * containment, and error-handling policy.
     */
    public static void deleteTree(Path root, boolean skipIfMissing, boolean requireWithinRoot,
            DeletePolicy policy) throws IOException {
        if (skipIfMissing) {
            boolean exists = policy == DeletePolicy.QUIET
                    ? Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    : Files.exists(root);
            if (!exists) return;
        }
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            if (policy == DeletePolicy.QUIET) return;
            throw e;
        }
        Path normalizedRoot = requireWithinRoot ? root.toAbsolutePath().normalize() : null;
        IOException failure = null;
        for (Path entry : paths) {
            if (requireWithinRoot && !entry.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
                throw new IOException("Scratch path escaped its root");
            }
            if (policy == DeletePolicy.QUIET) {
                deleteQuietly(entry);
                continue;
            }
            try {
                deleteWithRetry(entry);
            } catch (IOException e) {
                if (policy == DeletePolicy.FAIL_FAST) throw e;
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
