package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.SourceMetadata;
import java.io.*;
import java.util.*;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Immutable region inputs and file overlay, prepared before any destination writes. */
final class ArchiveRestorePlan implements AutoCloseable {
    record Region(String path, TarIndex.Entry base, LrhdRestoreData delta, int rx, int rz,
                  String contentHash, long sizeUncompressed, int frameLevel, SourceMetadata sourceMetadata) {
        RegionStream.Source source(LrhdRestoreData.StagedRegion staged) {
            RegionStream.Source source = consumer -> {
                if (base == null) {
                    for (int i = 0; i < 1024; i++) { consumer.begin(i, 0); consumer.end(); }
                } else try (InputStream in = base.open()) { LrhFormat.read(in, base.size(), consumer); }
            };
            return delta == null || staged == null ? source : delta.mergeStaged(rx, rz, source, staged);
        }
    }

    record FileRef(String path, String sourceHash, long sizeUncompressed,
                   SourceMetadata sourceMetadata, TarIndex.Entry object) {}

    @FunctionalInterface interface RegionTask<T> {
        T run(int index, RegionStream.Source source) throws IOException;
    }

    private record Coordinate(int rx, int rz) {}
    private static final String EMPTY_REGION_HASH = emptyRegionHash();
    final ArchiveSnapshot meta;
    final List<Region> regions = new ArrayList<>();
    final Map<String, FileRef> files = new LinkedHashMap<>();
    final Set<String> deleted = new LinkedHashSet<>();
    private final LrhdRestoreData.Fileset diffs = new LrhdRestoreData.Fileset();

    private ArchiveRestorePlan(ArchiveSnapshot meta) { this.meta = meta; }

    /**
     * Opens a plan and transfers ownership to the caller, which must close it.
     */
    @SuppressWarnings("resource") // Ownership transfers to the caller on successful return.
    static ArchiveRestorePlan open(Path full, Path diff) throws IOException {
        TarIndex base = TarIndex.read(full);
        ArchiveSnapshot fullMeta = base.snapshot("full");
        TarIndex delta = diff == null ? null : TarIndex.read(diff);
        ArchiveSnapshot meta = delta == null ? fullMeta : delta.snapshot("diff");
        if (delta != null && meta.baseEpoch != fullMeta.epoch)
            throw new IOException("Baseline mismatch: full.epoch=" + fullMeta.epoch + " != diff.baseEpoch=" + meta.baseEpoch);
        ArchiveRestorePlan plan = new ArchiveRestorePlan(meta);
        try {
            Map<String, TarIndex.Entry> baseRegionObjects = objectEntries(base, "objects/regions/", ".lrh");
            Map<String, TarIndex.Entry> baseFileObjects = objectEntries(base, "objects/files/", ".zst");
            Map<String, TarIndex.Entry> deltaFileObjects = delta == null ? Map.of()
                    : objectEntries(delta, "objects/files/", ".zst");
            for (var entry : base.entries.values()) {
                String name = entry.name();
                if (!name.equals("snapshot.bin") && !name.startsWith("objects/regions/")
                        && !name.startsWith("objects/files/"))
                    throw new IOException("Unexpected full archive entry: " + name);
            }
            Map<String, ArchiveSnapshot.RegionInfo> baseRegions = new HashMap<>();
            for (var info : fullMeta.regions) baseRegions.put(info.filename, info);
            Map<String, ArchiveSnapshot.FileInfo> baseFilesByPath = new HashMap<>();
            for (var info : fullMeta.files) baseFilesByPath.put(info.filename, info);
            Map<String, DiffComputer.FilesManifest.FileDiff> fileUpdates = new HashMap<>();
            if (delta != null) {
                for (var entry : delta.entries.values()) {
                    if (entry.name().equals("snapshot.bin")) continue;
                    if (entry.name().equals("files.bin")) {
                        var files = FilesManifestCodec.decode(entry.bytes());
                        if (files == null || files.files == null) throw new IOException("Invalid files.bin");
                        if (files.baseEpoch != fullMeta.epoch) throw new IOException("files.bin baseline mismatch");
                        for (var file : files.files) if (file.deleted) {
                            TarIndex.validatePath(file.path);
                            plan.deleted.add(file.path);
                        } else if (fileUpdates.putIfAbsent(file.path, file) != null)
                            throw new IOException("Duplicate files.bin path: " + file.path);
                    } else if (entry.name().endsWith("diff.lrhd")) {
                        String dir = entry.name().substring(0, entry.name().length() - "diff.lrhd".length());
                        if (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                        plan.diffs.add(dir, entry);
                    } else if (!entry.name().startsWith("objects/files/")) {
                        throw new IOException("Unexpected diff archive entry: " + entry.name());
                    }
                }
            }
            Set<String> seen = new HashSet<>();
            Set<String> targetRegionHashes = new HashSet<>();
            for (var info : meta.regions) {
                TarIndex.validatePath(info.filename);
                if (!seen.add(info.filename)) throw new IOException("Duplicate region metadata: " + info.filename);
                targetRegionHashes.add(info.contentHash);
                String dir = DiffComputer.directoryOf(info.filename);
                int slash = info.filename.lastIndexOf('/');
                int[] coords = AnvilFormat.parseRegionCoords(info.filename.substring(slash + 1));
                TarIndex.Entry targetObject = baseRegionObjects.get(info.contentHash);
                TarIndex.Entry sourceObject = targetObject;
                LrhdRestoreData regionDelta = null;
                if (targetObject == null) {
                    ArchiveSnapshot.RegionInfo before = baseRegions.get(info.filename);
                    sourceObject = before == null ? null : baseRegionObjects.get(before.contentHash);
                    regionDelta = plan.diffs.entries.get(dir);
                    if (delta != null && regionDelta == null && EMPTY_REGION_HASH.equals(info.contentHash)) {
                        // A newly added empty Region has no changed chunk frame; it can be synthesized.
                    } else if (delta == null || regionDelta == null || !hasCoordinate(regionDelta, coords[0], coords[1]))
                        throw new IOException("Missing Region object or LRHD data: " + info.filename);
                }
                plan.regions.add(new Region(info.filename, sourceObject, regionDelta, coords[0], coords[1],
                        info.contentHash, info.sizeUncompressed,
                        sourceObject == null ? 0
                                : LrhFormat.level(sourceObject.prefix(LrhFormat.LEVEL_PREFIX_SIZE)),
                        info.sourceMetadata));
            }
            Set<String> targetPaths = new HashSet<>(seen);
            Set<String> seenFiles = new HashSet<>();
            Set<String> targetFileHashes = new HashSet<>();
            for (var info : meta.files) {
                TarIndex.validatePath(info.filename);
                if (!seenFiles.add(info.filename)) throw new IOException("Duplicate file metadata: " + info.filename);
                targetFileHashes.add(info.sourceHash);
                targetPaths.add(info.filename);
                TarIndex.Entry object = baseFileObjects.get(info.sourceHash);
                if (object == null) object = deltaFileObjects.get(info.sourceHash);
                if (object == null) throw new IOException("Missing file object: " + info.filename + " (" + info.sourceHash + ")");
                plan.files.put(info.filename, new FileRef(info.filename, info.sourceHash, info.sizeUncompressed,
                        info.sourceMetadata, object));
                var update = fileUpdates.remove(info.filename);
                var previous = baseFilesByPath.get(info.filename);
                boolean changed = previous == null || !previous.sourceHash.equals(info.sourceHash)
                        || !previous.sourceMetadata.equals(info.sourceMetadata);
                if (delta != null && changed) {
                    if (update == null || !info.sourceHash.equals(update.sourceHash)
                            || info.sizeUncompressed != update.sizeUncompressed
                            || !info.sourceMetadata.equals(update.sourceMetadata))
                        throw new IOException("files.bin does not match target file metadata: " + info.filename);
                } else if (update != null) {
                    throw new IOException("Unexpected files.bin update: " + info.filename);
                }
            }
            for (var old : fullMeta.regions) if (!targetPaths.contains(old.filename)) plan.deleted.add(old.filename);
            for (var old : fullMeta.files) if (!targetPaths.contains(old.filename)) plan.deleted.add(old.filename);
            for (String deleted : plan.deleted)
                if (targetPaths.contains(deleted)) throw new IOException("Deleted path is present in target manifest: " + deleted);
            if (!fileUpdates.isEmpty()) throw new IOException("files.bin references absent target paths: " + fileUpdates.keySet());
            if (delta == null) {
                if (!baseRegionObjects.keySet().equals(targetRegionHashes))
                    throw new IOException("Full archive Region objects do not match snapshot manifest");
                if (!baseFileObjects.keySet().equals(targetFileHashes))
                    throw new IOException("Full archive file objects do not match snapshot manifest");
            } else {
                if (!targetFileHashes.containsAll(deltaFileObjects.keySet()))
                    throw new IOException("Diff archive contains an unreferenced file object");
                for (String hash : deltaFileObjects.keySet())
                    if (baseFileObjects.containsKey(hash))
                        throw new IOException("Diff archive duplicates a baseline file object: " + hash);
            }
            plan.validateTargets();
            return plan;
        } catch (IOException | RuntimeException | Error failure) {
            try { plan.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static Map<String, TarIndex.Entry> objectEntries(TarIndex index, String prefix, String suffix)
            throws IOException {
        Map<String, TarIndex.Entry> result = new HashMap<>();
        for (var entry : index.entries.values()) {
            if (!entry.name().startsWith(prefix)) continue;
            if (!entry.name().endsWith(suffix)) throw new IOException("Invalid content object path: " + entry.name());
            String hash = entry.name().substring(prefix.length(), entry.name().length() - suffix.length());
            if (!hash.matches("[0-9a-f]{32}")) throw new IOException("Invalid content object identity: " + entry.name());
            result.put(hash, entry);
        }
        return result;
    }

    private static String emptyRegionHash() {
        try {
            return HashUtil.bytesToHex(RegionStream.inspect(consumer -> {
                for (int i = 0; i < 1024; i++) {
                    consumer.begin(i, 0);
                    consumer.end();
                }
            }).hash());
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
