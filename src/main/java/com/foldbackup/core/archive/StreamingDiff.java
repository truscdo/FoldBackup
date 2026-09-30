package com.foldbackup.core.archive;

import com.foldbackup.core.HashUtil;
import com.foldbackup.core.ProgressTracker;
import com.foldbackup.core.StreamingHash;
import com.foldbackup.core.format.*;
import com.foldbackup.core.store.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Plans contain LRHD metadata only. NBT goes from LRH directly into the LRHD frame. */
final class StreamingDiff implements AutoCloseable {
    record Chunks(int[] lengths, int[] timestamps, String[] hashes) {}
    record Plan(RegionEntry region, int[] lengths, int[] timestamps, String[] hashes, boolean[] selected) {}
    final Map<String, List<DiffPlanStore.Ref>> directories = new TreeMap<>();
    final List<FileEntry> fileObjects = new ArrayList<>();
    byte[] filesBin;
    private final BlobStore blobs;

    private final DiffPlanStore plans;

    StreamingDiff(BlobStore blobs, Path scratch) throws IOException {
        this.blobs = blobs; plans = new DiffPlanStore(scratch);
    }

    @Override public void close() throws IOException { plans.close(); }

    void compare(SnapshotManifest base, SnapshotManifest target, int threads, ProgressTracker progress) throws IOException {
        Map<String, RegionEntry> old = new HashMap<>();
        if (base.getRegions() != null) for (var entry : base.getRegions()) old.put(entry.getFilename(), entry);
        Set<String> baselineRegionHashes = new HashSet<>();
        if (base.getRegions() != null) for (var entry : base.getRegions()) baselineRegionHashes.add(entry.getContentHash());
        var targets = target.getRegions() == null ? List.<RegionEntry>of() : target.getRegions();
        // The base lookup remains immutable while comparison workers run.
        progress.stage("Comparing regions", targets.size());
        int workers = DiffResourceBudget.scanWorkers(targets.size(), threads, ArchiveDefaults.exportMemoryBudget());
        ArchiveTasks.<Plan>collect(targets.size(), workers, 2, index -> {
            RegionEntry entry = targets.get(index);
            RegionEntry previous = old.get(entry.getFilename());
            if (baselineRegionHashes.contains(entry.getContentHash())) return null;
            if (previous != null && previous.getContentHash().equals(entry.getContentHash())) return null;
            Chunks current = inspect(entry);
            Chunks before = previous == null
                    ? new Chunks(new int[1024], new int[1024], new String[1024]) : inspect(previous);
            boolean[] selected = new boolean[1024]; boolean any = false;
            for (int i = 0; i < 1024; i++) {
                selected[i] = !Objects.equals(current.hashes[i], before.hashes[i]); any |= selected[i];
            }
            return any ? new Plan(entry, current.lengths, current.timestamps, current.hashes, selected) : null;
        }, (index, plan) -> {
            if (plan != null) directories.computeIfAbsent(DiffComputer.directoryOf(plan.region.getFilename()),
                    key -> new ArrayList<>()).add(plans.append(plan));
            progress.done(targets.get(index).getFilename());
        }, null);
        for (var entry : targets) old.remove(entry.getFilename());
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

    private Chunks inspect(RegionEntry entry) throws IOException {
        int[] lengths = new int[1024], timestamps = new int[1024];
        String[] hashes = new String[1024];
        LrhFormat.read(blobs.getRootDir().resolve(entry.getBlobPath()), new RegionStream.Consumer() {
            int index; StreamingHash.Hash128 hash;
            public void begin(int i, int timestamp) {
                index = i; timestamps[index] = timestamp; hash = new StreamingHash.Hash128();
            }
            public void data(byte[] bytes, int off, int len) { lengths[index] += len; hash.update(bytes, off, len); }
            public void end() { if (lengths[index] > 0) hashes[index] = HashUtil.bytesToHex(hash.digest()); }
        });
        return new Chunks(lengths, timestamps, hashes);
    }

    void writeDirectories(TarWriter tar, int level, int threads, ProgressTracker progress) throws IOException {
        var groups = new ArrayList<>(directories.entrySet());
        if (groups.isEmpty()) return;
        var limits = DiffResourceBudget.compression(groups.size(), threads, level, ArchiveDefaults.exportMemoryBudget());
        OrderedCompressionPipeline.<LrhdFrameProducer.Summary>run(groups.size(), limits.workers(), limits.window(),
                DiffResourceBudget.BLOCK_SIZE, limits.bufferBytes(),
                (index, output) -> {
                    try (var frame = new LrhdFrameProducer(output, level)) {
                        for (var ref : groups.get(index).getValue()) {
                            StreamIo.checkCancelled();
                            frame.region(plans.read(ref), consumer -> LrhFormat.read(
                                    blobs.getRootDir().resolve(ref.source().getBlobPath()), consumer));
                        }
                        return frame.finish();
                    }
                }, (index, result) -> {
                    var group = groups.get(index);
                    String name = group.getKey().isEmpty() ? "diff.lrhd" : group.getKey() + "/diff.lrhd";
                    progress.current(name);
                    tar.putEntry(name, output -> {
                        var archive = new LrhdStreamWriter.ArchiveWriter(output, level, visitor -> {
                            for (var ref : group.getValue()) visitor.accept(plans.read(ref));
                        });
                        archive.finish(result.drainTo(StreamIo.output(output)));
                    });
                    progress.done(name);
                });
    }
}
