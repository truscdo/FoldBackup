package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.LogUtil;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Removes the extra paths selected by the user.
 * <p>
 * Deletion runs after every write and verification. A failure does not roll back what was already
 * removed: the remaining candidates are still attempted and the failures are reported together.
 */
public final class RestoreDeleter {
    private static final LogUtil LOG = LogUtil.getLogger(RestoreDeleter.class);

    private RestoreDeleter() {
    }

    private record Failure(String path, IOException error) {
    }

    public static void delete(WorldTarget target, RestorePlan plan, ProgressListener listener) throws IOException {
        Path root = target.directory();
        var progress = new ProgressTracker(listener);
        progress.stage("Deleting", plan.deletions().size());

        List<Failure> failures = new ArrayList<>();
        for (String path : plan.deletions()) {
            progress.current(path);
            try {
                Files.deleteIfExists(root.resolve(path));
            } catch (IOException e) {
                failures.add(new Failure(path, e));
                LOG.error("Failed to delete {}: {}", path, e);
            }
            progress.done(path);
        }

        for (String directory : plan.removedDirectories()) {
            try {
                Files.deleteIfExists(root.resolve(directory));
            } catch (DirectoryNotEmptyException ignored) {
                // Only a concurrent writer can refill a directory the check phase proved empty.
            } catch (IOException e) {
                failures.add(new Failure(directory, e));
                LOG.error("Failed to delete directory {}: {}", directory, e);
            }
        }

        if (!failures.isEmpty()) {
            // The remaining candidates were still attempted; report every path that failed.
            String paths = failures.stream().map(Failure::path).collect(Collectors.joining(", "));
            throw new IOException("Failed to delete " + failures.size() + " path(s): " + paths,
                    failures.get(0).error());
        }
    }
}
