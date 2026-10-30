package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.io.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Immutable region inputs and file overlay, prepared before any destination writes. */
final class ArchiveRestorePlan implements AutoCloseable {
    record Region(String path, Baseline.RegionObject base, LrhdRestoreData delta, int rx, int rz,
                  String contentHash, long sizeUncompressed, int frameLevel, SourceMetadata sourceMetadata) {
        RegionStream.Source source(LrhdRestoreData.StagedRegion staged) {
            RegionStream.Source source = base == null ? EMPTY_REGION : base.decoded();
            return delta == null || staged == null ? source : delta.mergeStaged(rx, rz, source, staged);
        }
    }

    record FileRef(String path, String sourceHash, long sizeUncompressed,
                   SourceMetadata sourceMetadata, FileSource source) {}

    @FunctionalInterface interface RegionTask<T> {
        T run(int index, RegionStream.Source source) throws IOException;
    }

    private record Coordinate(int rx, int rz) {}

    /** 基线中不存在的 Region（新增）合成空内容：1024 个空区块、时间戳 0。 */
    private static final RegionStream.Source EMPTY_REGION = consumer -> {
        for (int i = 0; i < 1024; i++) {
            consumer.begin(i, 0);
            consumer.end();
        }
    };
    private static final String EMPTY_REGION_HASH = emptyRegionHash();

    final List<Region> regions = new ArrayList<>();
    final Map<String, FileRef> files = new LinkedHashMap<>();
    final Set<String> deleted = new LinkedHashSet<>();
    private final LrhdRestoreData.Fileset diffs = new LrhdRestoreData.Fileset();

    private ArchiveRestorePlan() {}

    /**
     * Builds the plan from a baseline plus an optional differential archive.
     * <p>
     * {@code target} is the target-state manifest (the archive being imported, restored or
     * verified); {@code diff} is its {@link TarIndex}, or {@code null} when the target is the
     * baseline itself. Every baseline object and LRHD frame referenced by the target is created
     * here, so the same merge implementation serves import, restore and verification.
     */
    @SuppressWarnings("resource") // Ownership transfers to the caller on successful return.
    static ArchiveRestorePlan open(Baseline base, ArchiveSnapshot target, TarIndex diff) throws IOException {
        if (diff != null && target.baseEpoch != base.epoch())
            throw new IOException("Baseline mismatch: baseline.epoch=" + base.epoch()
                    + " != diff.baseEpoch=" + target.baseEpoch);
        ArchiveRestorePlan plan = new ArchiveRestorePlan();
        try {
            Map<String, TarIndex.Entry> diffFileObjects = diff == null ? Map.of()
                    : objectEntries(diff, "objects/files/", ".zst");
            Map<String, DiffComputer.FilesManifest.FileDiff> fileUpdates = new HashMap<>();
            if (diff != null) plan.diffEntries(base, diff, fileUpdates);
            Map<String, RegionInfo> baseRegions = base.regions();
            Map<String, FileInfo> baseFiles = base.files();
            Set<String> targetPaths = new HashSet<>();
            plan.mergeRegions(base, target, diff, targetPaths);
            plan.mergeFiles(base, target, diff, diffFileObjects, fileUpdates, targetPaths);
            for (var old : baseRegions.values()) if (!targetPaths.contains(old.filename())) plan.deleted.add(old.filename());
            for (var old : baseFiles.values()) if (!targetPaths.contains(old.filename())) plan.deleted.add(old.filename());
            for (String deleted : plan.deleted)
                if (targetPaths.contains(deleted)) throw new IOException("Deleted path is present in target manifest: " + deleted);
            if (!fileUpdates.isEmpty()) throw new IOException("files.bin references absent target paths: " + fileUpdates.keySet());
            if (diff != null) {
                Set<String> targetFileHashes = new HashSet<>();
                for (var info : target.files) targetFileHashes.add(info.sourceHash);
                if (!targetFileHashes.containsAll(diffFileObjects.keySet()))
                    throw new IOException("Diff archive contains an unreferenced file object");
                for (String hash : diffFileObjects.keySet())
                    if (base.fileSource(hash) != null)
                        throw new IOException("Diff archive duplicates a baseline file object: " + hash);
            }
            plan.validateTargets();
            return plan;
        } catch (IOException | RuntimeException | Error failure) {
            try { plan.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Merges the target Region list against the baseline: unchanged content is read from the
     * baseline, changed Regions carry their LRHD frame. Every path must be unique, so the set
     * is shared with the file pass and later deletion derivation.
     */
    private void mergeRegions(Baseline base, ArchiveSnapshot target, TarIndex diff, Set<String> targetPaths)
            throws IOException {
        Map<String, RegionInfo> baseRegions = base.regions();
        for (var info : target.regions) {
            TarIndex.validatePath(info.filename);
            if (!targetPaths.add(info.filename)) throw new IOException("Duplicate region metadata: " + info.filename);
            SourceMetadata metadata = ArchiveObjects.metadata(info.sourceMetadata);
            String dir = DiffComputer.directoryOf(info.filename);
            int slash = info.filename.lastIndexOf('/');
            int[] coords = AnvilFormat.parseRegionCoords(info.filename.substring(slash + 1));
            Baseline.RegionObject baseObject = base.regionObject(info.contentHash);
            int frameLevel = baseObject == null ? 0 : base.regionLevel(info.contentHash);
            LrhdRestoreData regionDelta = null;
            if (baseObject == null) {
                RegionInfo before = baseRegions.get(info.filename);
                if (before != null) {
                    baseObject = base.regionObject(before.contentHash());
                    if (baseObject == null)
                        throw new IOException("Baseline Region object is missing: " + info.filename
                                + " (" + before.contentHash() + ")");
                    frameLevel = base.regionLevel(before.contentHash());
                }
                regionDelta = diffs.entries.get(dir);
                if (diff != null && regionDelta == null && EMPTY_REGION_HASH.equals(info.contentHash)) {
                    // A newly added empty Region has no changed chunk frame; it can be synthesized.
                } else if (diff == null || regionDelta == null || !hasCoordinate(regionDelta, coords[0], coords[1]))
                    throw new IOException("Missing Region object or LRHD data: " + info.filename);
            }
            regions.add(new Region(info.filename, baseObject, regionDelta, coords[0], coords[1],
                    info.contentHash, info.sizeUncompressed, frameLevel, metadata));
        }
    }

    /**
     * Merges the target file list against the baseline: unchanged files reuse the baseline
     * object, changed ones come from the differential archive. A path recorded in
     * {@code files.bin} must match the target metadata exactly, and no update may describe a
     * path the target does not declare.
     */
    private void mergeFiles(Baseline base, ArchiveSnapshot target, TarIndex diff,
            Map<String, TarIndex.Entry> diffFileObjects,
            Map<String, DiffComputer.FilesManifest.FileDiff> fileUpdates, Set<String> targetPaths)
            throws IOException {
        Set<String> seen = new HashSet<>();
        Map<String, FileInfo> baseFiles = base.files();
        for (var info : target.files) {
            TarIndex.validatePath(info.filename);
            if (!seen.add(info.filename)) throw new IOException("Duplicate file metadata: " + info.filename);
            targetPaths.add(info.filename);
            SourceMetadata metadata = ArchiveObjects.metadata(info.sourceMetadata);
            FileSource source = base.fileSource(info.sourceHash);
            if (source == null) {
                TarIndex.Entry object = diffFileObjects.get(info.sourceHash);
                if (object == null)
                    throw new IOException("Missing file object: " + info.filename + " (" + info.sourceHash + ")");
                source = object::open;
            }
            files.put(info.filename, new FileRef(info.filename, info.sourceHash,
                    info.sizeUncompressed, metadata, source));
            var update = fileUpdates.remove(info.filename);
            var previous = baseFiles.get(info.filename);
            boolean changed = previous == null || !previous.sourceHash().equals(info.sourceHash)
                    || !previous.metadata().equals(metadata);
            if (diff != null && changed) {
                if (update == null || !info.sourceHash.equals(update.sourceHash)
                        || info.sizeUncompressed != update.sizeUncompressed
                        || !metadata.equals(ArchiveObjects.metadata(update.sourceMetadata)))
                    throw new IOException("files.bin does not match target file metadata: " + info.filename);
            } else if (update != null) {
                throw new IOException("Unexpected files.bin update: " + info.filename);
            }
        }
    }

    /**
     * Classifies one differential archive's entries before any target state is merged:
     * {@code files.bin} yields deletions and per-path updates, {@code diff.lrhd} frames are
     * indexed by directory. Content objects are collected separately by
     * {@link #objectEntries}.
     */
    private void diffEntries(Baseline base, TarIndex diff,
            Map<String, DiffComputer.FilesManifest.FileDiff> fileUpdates) throws IOException {
        for (var entry : diff.entries.values()) {
            String name = entry.name();
            if (name.equals("snapshot.bin")) continue;
            if (name.equals("files.bin")) {
                var files = FilesManifestCodec.decode(entry.bytes());
                if (files == null || files.files == null) throw new IOException("Invalid files.bin");
                if (files.baseEpoch != base.epoch()) throw new IOException("files.bin baseline mismatch");
                for (var file : files.files) if (file.deleted) {
                    TarIndex.validatePath(file.path);
                    deleted.add(file.path);
                } else if (fileUpdates.putIfAbsent(file.path, file) != null)
                    throw new IOException("Duplicate files.bin path: " + file.path);
            } else if (name.endsWith("diff.lrhd")) {
                String dir = name.substring(0, name.length() - "diff.lrhd".length());
                if (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                diffs.add(dir, entry);
            } else if (!name.startsWith("objects/files/")) {
                throw new IOException("Unexpected diff archive entry: " + name);
            }
        }
    }

    private static Map<String, TarIndex.Entry> objectEntries(TarIndex index, String prefix, String suffix)
            throws IOException {
        Map<String, TarIndex.Entry> result = new HashMap<>();
        for (var entry : index.entries.values()) {
            if (!entry.name().startsWith(prefix)) continue;
            result.put(ArchiveObjects.hashOf(entry.name(), prefix, suffix), entry);
        }
        return result;
    }

    private static String emptyRegionHash() {
        try {
            return HashUtil.bytesToHex(RegionStream.inspect(EMPTY_REGION).hash());
        } catch (IOException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static boolean hasCoordinate(LrhdRestoreData delta, int rx, int rz) {
        for (var region : delta.regions) if (region.rx == rx && region.rz == rz) return true;
        return false;
    }

    Map<String, FileRef> files() { return Collections.unmodifiableMap(files); }

    /** Region entries represented by an LRHD change record. */
    Set<Integer> changedRegionIndexes() {
        var coordinates = new IdentityHashMap<LrhdRestoreData, Set<Coordinate>>();
        for (var delta : diffs.entries.values()) {
            var changed = new HashSet<Coordinate>();
            for (var indexed : delta.regions) changed.add(new Coordinate(indexed.rx, indexed.rz));
            coordinates.put(delta, changed);
        }
        var indexes = new LinkedHashSet<Integer>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            if (region.delta() != null && coordinates.getOrDefault(region.delta(), Set.of())
                    .contains(new Coordinate(region.rx(), region.rz()))) indexes.add(i);
        }
        return Collections.unmodifiableSet(indexes);
    }

    /**
     * 本计划全部 Region 的源帧级别上界；合成 Region（无基础 LRH 帧）计 0。
     * <p>
     * 供不经 {@link ArchiveRestorePipeline} 的批处理调用点（非合并导入、快照导出）取整批上界。
     */
    int maxSourceFrameLevel() {
        int level = 0;
        for (Region region : regions) level = Math.max(level, region.frameLevel());
        return level;
    }

    /**
     * Stages each LRHD frame sequentially while bounded Region workers merge
     * completed stages. Frame verification gates progress and publication.
     */
    <T> List<T> runRegions(ArchiveTasks.TaskWork taskWork, int threads,
            java.util.function.Consumer<ArchiveTasks.Metrics> metrics,
            IntConsumer completionListener, RegionTask<T> task) throws IOException {
        return runRegions(taskWork, threads, metrics, index -> true, completionListener, task);
    }

    /**
     * Runs selected output Regions while every LRHD frame is still consumed and verified.
     * Results preserve plan indexes; unselected indexes contain {@code null}.
     */
    <T> List<T> runRegions(ArchiveTasks.TaskWork taskWork, int threads,
            java.util.function.Consumer<ArchiveTasks.Metrics> metrics,
            IntPredicate includeRegion, IntConsumer completionListener, RegionTask<T> task) throws IOException {
        return new ArchiveRestorePipeline(regions, diffs.entries.values()).run(taskWork, threads, metrics,
                includeRegion, completionListener, task);
    }

    private void validateTargets() throws IOException {
        var targets = new WorldRestoreTargets();
        for (Region region : regions) {
            targets.addRegion(region.path(), region.rx(), region.rz(), "Conflicting region path: ");
        }
        for (String path : files.keySet()) {
            targets.addFile(path);
        }
        targets.validateParents();
    }

    @Override public void close() throws IOException { diffs.close(); }
}
