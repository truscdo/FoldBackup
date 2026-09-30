package io.github.truscdo.foldbackup.test;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Owns cleanup instead of JUnit's single-pass deletion; permanent failures still fail the test. */
public final class RetryingTempDirFactory implements TempDirFactory {
    private Path directory;

    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
        directory = Files.createTempDirectory("foldbackup-junit-").toAbsolutePath().normalize();
        return directory;
    }

    @Override
    public void close() throws IOException {
        if (directory != null) retryCleanup(() -> deleteTree(directory), 20, 100);
    }

    @FunctionalInterface
    interface Cleanup { void run() throws IOException; }

    static void retryCleanup(Cleanup cleanup, int attempts, long delayMillis) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                cleanup.run();
                return;
            } catch (DirectoryNotEmptyException | AccessDeniedException failure) {
                if (attempt >= attempts) throw failure;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        // Only visit this factory's generated directory, without following symbolic links.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
