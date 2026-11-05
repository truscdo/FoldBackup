package io.github.truscdo.foldbackup.core.restore;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.StreamingHash;
import io.github.truscdo.foldbackup.core.format.McaHasher;
import io.github.truscdo.foldbackup.core.format.McaStreamWriter;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lands restore entries one by one.
 * <p>
 * Each entry is written next to its destination, verified against the recorded content identity,
 * then renamed into place. A failure drops only its own temporary files; entries already landed
 * stay in place. Regions are written as a unit: the {@code .mcc} files produced by the rewrite are
 * moved in, and stale {@code .mcc} files of the same unit are removed regardless of the delete
 * option, because a region unit is always replaced as a whole.
 */
public final class RestoreWriter {
    private RestoreWriter() {
    }

    public static void write(WorldTarget target, RestoreSource source, RestorePlan plan, int threads,
            int compressionLevel, ProgressListener listener) throws IOException {
        Path root = target.directory();
        target.ensureDirectory();
        var progress = new ProgressTracker(listener);

        progress.stage("Restoring files", plan.files().size());
        for (RestoreEntry entry : plan.files()) {
            progress.current(entry.path());
            writeFile(root, source, entry);
            progress.done(entry.path());
        }

        progress.stage("Restoring regions", plan.regions().size());
        if (!plan.regions().isEmpty()) {
            source.readRegions(plan.regions(), (entry, region) -> {
                writeRegion(root, entry, region, compressionLevel);
                synchronized (progress) {
                    progress.done(entry.path());
                }
            }, threads);
        }
    }

    private static void writeFile(Path root, RestoreSource source, RestoreEntry entry) throws IOException {
        Path destination = root.resolve(entry.path());
        Files.createDirectories(destination.getParent());
        Path temp = Files.createTempFile(destination.getParent(),
                "." + destination.getFileName() + ".", ".foldbackup");
        try {
            var hash = new StreamingHash.Hash128();
            long total = 0;
            byte[] buffer = new byte[65536];
            try (InputStream input = source.openFile(entry); OutputStream output = Files.newOutputStream(temp)) {
                for (int n; (n = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, n);
                    hash.update(buffer, 0, n);
                    total += n;
                }
            }
            if (total != entry.sizeUncompressed() || !entry.contentHash().equals(hash.fileHex()))
                throw new IOException("Restored file identity mismatch: " + entry.path());
            applyRecordedTime(temp, entry);
            move(temp, destination);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeRegion(Path root, RestoreEntry entry, RegionStream.Source source, int compressionLevel)
            throws IOException {
        Path destination = root.resolve(entry.path());
        Path directory = destination.getParent();
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, ".foldbackup-");
        try {
            Path staged = staging.resolve(destination.getFileName());
            McaStreamWriter.write(staged, entry.regionX(), entry.regionZ(), recordedTime(entry), compressionLevel,
                    verified(entry.path(), entry.contentHash(), source));

            move(staged, destination);
            Set<String> produced = listMcc(staging);
            for (String name : produced) move(staging.resolve(name), directory.resolve(name));
            for (Path mcc : McaHasher.findMccFilesForRegion(directory, entry.regionX(), entry.regionZ())) {
                if (!produced.contains(mcc.getFileName().toString())) Files.deleteIfExists(mcc);
            }
        } finally {
            deleteTree(staging);
        }
    }

    private static Set<String> listMcc(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".mcc"))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }

    /**
     * Re-reads the produced region once more to prove its identity. The check happens before the
     * file leaves the staging directory, so a mismatch never replaces the target.
     */
    private static RegionStream.Source verified(String path, String expectedHash, RegionStream.Source source) {
        return consumer -> {
            var check = new RegionStream.Accumulator();
            source.read(new RegionStream.Consumer() {
                @Override public void begin(int index, int timestamp) throws IOException {
                    check.begin(index, timestamp);
                    consumer.begin(index, timestamp);
                }

                @Override public void present(boolean present) throws IOException {
                    check.present(present);
                    consumer.present(present);
                }

                @Override public void data(byte[] bytes, int offset, int length) throws IOException {
                    check.data(bytes, offset, length);
                    consumer.data(bytes, offset, length);
                }

                @Override public void end() throws IOException {
                    check.end();
                    consumer.end();
                }
            });
            if (!expectedHash.equals(HashUtil.bytesToHex(check.finish().hash())))
                throw new IOException("Restored Region identity mismatch: " + path);
        };
    }

    private static long recordedTime(RestoreEntry entry) {
        for (SourceMetadata.SourceFile file : entry.sourceMetadata().files()) {
            if (file.relativePath().equalsIgnoreCase(entry.path())) return file.modifiedMillis();
        }
        return System.currentTimeMillis();
    }

    private static void applyRecordedTime(Path file, RestoreEntry entry) throws IOException {
        for (SourceMetadata.SourceFile recorded : entry.sourceMetadata().files()) {
            if (recorded.relativePath().equalsIgnoreCase(entry.path())) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(recorded.modifiedMillis()));
                return;
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(paths::add);
        } // Close enumeration handles before deleting on Windows.
        paths.sort(java.util.Comparator.reverseOrder());
        for (Path path : paths) delete(path);
    }

    /**
     * Windows can briefly report a directory emptied by a move as not empty, or deny access while a
     * scan still holds it. Bounded retries keep that transient state from failing a restore whose
     * content has already landed; cleanup still runs to completion when the thread is interrupted.
     */
    private static void delete(Path path) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (DirectoryNotEmptyException | AccessDeniedException e) {
                    if (attempt == 19) throw e;
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException cancellation) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
