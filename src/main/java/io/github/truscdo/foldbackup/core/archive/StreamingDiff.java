package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Manifest planning followed by one {@code <dir>/diff.lrhd} frame at a time.
 * <p>
 * The size of a directory's changed payload is only known while decoding it, so it cannot size a
 * worker pool beforehand. Directories are therefore encoded in sequence, each asking for the whole
 * frame allowance; within a frame the comparison keeps decoding while the encoder's native worker
 * pool compresses.
 */
final class StreamingDiff implements AutoCloseable {
    record Pair(RegionEntry target, RegionEntry base, int rx, int rz) {}
    final Map<String, List<Pair>> directories = new TreeMap<>();
    final List<FileEntry> fileObjects = new ArrayList<>();
    byte[] filesBin;
    private final BlobStore blobs;

    private final Path scratch;
    private boolean closed;

    StreamingDiff(BlobStore blobs, Path scratch) {
        this.blobs = blobs; this.scratch = scratch;
    }

    @Override public void close() { closed = true; }

    void compare(SnapshotManifest base, SnapshotManifest target, ProgressTracker progress) throws IOException {
        if (closed) throw new IOException("Diff generator closed");
        Map<String, RegionEntry> old = new HashMap<>();
        if (base.getRegions() != null) for (var entry : base.getRegions()) old.put(entry.getFilename(), entry);
        Set<String> baselineRegionHashes = new HashSet<>();
        if (base.getRegions() != null) for (var entry : base.getRegions()) baselineRegionHashes.add(entry.getContentHash());
        var targets = target.getRegions() == null ? List.<RegionEntry>of() : target.getRegions();
        progress.stage("Comparing regions", targets.size());
        for (var entry : targets) {
            StreamIo.checkCancelled();
            var previous = old.remove(entry.getFilename());
            if (!baselineRegionHashes.contains(entry.getContentHash())) {
                int[] coords = AnvilFormat.parseRegionCoords(Path.of(entry.getFilename()).getFileName().toString());
                directories.computeIfAbsent(DiffComputer.directoryOf(entry.getFilename()), key -> new ArrayList<>())
                        .add(new Pair(entry, previous, coords[0], coords[1]));
            }
            progress.done(entry.getFilename());
        }
        for (var directory : directories.values()) directory.sort((a, b) ->
                LrhdFormat.compareRegions(a.rx(), a.rz(), b.rx(), b.rz()));
        progress.stage("Comparing file manifests", 1);
        var manifest = new DiffComputer.FilesManifest(); manifest.baseEpoch = base.getEpochSeconds(); manifest.files = new ArrayList<>();
        for (String deleted : old.keySet()) manifest.files.add(new DiffComputer.FilesManifest.FileDiff(deleted,
                null, 0, SourceMetadata.empty(), true));
        Map<String, FileEntry> previousFiles = new LinkedHashMap<>();
        if (base.getExtraFiles() != null) for (var file : base.getExtraFiles()) previousFiles.put(file.getFilename(), file);
        Set<String> baselineFileHashes = new HashSet<>();
        if (base.getExtraFiles() != null) for (var file : base.getExtraFiles()) baselineFileHashes.add(file.getSourceHash());
        Set<String> writtenObjects = new HashSet<>();
        if (target.getExtraFiles() != null) for (var file : target.getExtraFiles()) {
            var previous = previousFiles.remove(file.getFilename());
            boolean changed = previous == null || !previous.getSourceHash().equals(file.getSourceHash())
                    || !previous.getSourceMetadata().equals(file.getSourceMetadata());
            if (changed) {
                manifest.files.add(new DiffComputer.FilesManifest.FileDiff(file.getFilename(), file.getSourceHash(),
                        file.getSizeUncompressed(), file.getSourceMetadata(), false));
                if (!baselineFileHashes.contains(file.getSourceHash()) && writtenObjects.add(file.getSourceHash()))
                    fileObjects.add(file);
            }
        }
        for (String deleted : previousFiles.keySet()) manifest.files.add(new DiffComputer.FilesManifest.FileDiff(deleted,
                null, 0, SourceMetadata.empty(), true));
        if (!manifest.files.isEmpty()) filesBin = FilesManifestCodec.encode(manifest);
        progress.done("files.bin");
    }

    void writeDirectories(TarWriter tar, int level, int threads, ProgressTracker progress) throws IOException {
        if (closed) throw new IOException("Diff generator closed");
        if (directories.isEmpty()) return;
        var limits = DiffResourceBudget.pipeline(threads, level, ArchiveDefaults.exportMemoryBudget());
        for (var group : directories.entrySet()) {
            StreamIo.checkCancelled();
            String prefix = group.getKey();
            String name = prefix.isEmpty() ? "diff.lrhd" : prefix + "/diff.lrhd";
            progress.current(name);
            var tasks = new ArrayList<DiffRegionPipeline.RegionTask>(group.getValue().size());
            for (var pair : group.getValue()) {
                RegionEntry base = pair.base();
                tasks.add(new DiffRegionPipeline.RegionTask(pair.rx(), pair.rz(),
                        base == null ? null : consumer -> LrhFormat.read(
                                blobs.getRootDir().resolve(base.getBlobPath()), consumer),
                        consumer -> LrhFormat.read(blobs.getRootDir().resolve(pair.target().getBlobPath()),
                                consumer)));
            }
            Path spool = scratch.resolve("lrhd-" + UUID.randomUUID() + ".index");
            try {
                tar.putEntry(name, channel -> {
                    try (var writer = new LrhdEncodingWriter(StreamIo.output(channel), spool, level,
                            limits.zstdWorkers())) {
                        DiffRegionPipeline.run(tasks, limits.chunkBytes(), scratch, writer);
                        // A directory with no changed chunk still owns an entry; roll it back.
                        if (writer.finish() == 0) throw new TarWriter.EntryCancelledException();
                    }
                });
                progress.done(name);
            } finally {
                Files.deleteIfExists(spool);
            }
        }
    }
}
