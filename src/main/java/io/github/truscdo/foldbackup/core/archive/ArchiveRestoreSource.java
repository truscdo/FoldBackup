package io.github.truscdo.foldbackup.core.archive;

import com.github.luben.zstd.ZstdInputStream;
import io.github.truscdo.foldbackup.core.restore.RestoreEntry;
import io.github.truscdo.foldbackup.core.restore.RestoreSource;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Restore source backed by a full archive with an optional differential archive.
 * <p>
 * Only the plan (entry metadata) is built here; region and file content is produced on demand.
 * Frames whose directory has no selected region are never opened.
 */
public final class ArchiveRestoreSource implements RestoreSource {
    private final ArchiveBaseline base;
    private final TarIndex diff;
    private final ArchiveRestorePlan plan;
    private final List<RestoreEntry> entries;
    private final List<RestoreEntry> regionEntries;

    public static ArchiveRestoreSource open(Path archiveRoot, Path archiveFile) throws IOException {
        if (!Files.isDirectory(archiveRoot)) throw new IOException("Archive root not found: " + archiveRoot);
        if (!Files.isRegularFile(archiveFile)) throw new IOException("Archive file not found: " + archiveFile);
        ArchiveSnapshot meta = ArchiveFormatUtil.readSnapshot(archiveFile);
        if (meta == null) throw new IOException("Cannot read snapshot metadata from archive: " + archiveFile);

        Path fullTar;
        Path diffTar = null;
        if (meta.isFull()) {
            fullTar = archiveFile;
        } else if (meta.isDiff()) {
            try {
                fullTar = ArchiveTarScanner.fullAtEpoch(ArchiveTarScanner.scan(archiveRoot), meta.baseEpoch);
            } catch (IOException e) {
                String expected = ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(meta.baseEpoch));
                throw new IOException("Required baseline archive " + expected + " is missing", e);
            }
            diffTar = archiveFile;
        } else {
            throw new IOException("Unknown archive type in " + archiveFile + ": " + meta.type);
        }
        return new ArchiveRestoreSource(fullTar, diffTar);
    }

    private ArchiveRestoreSource(Path fullTar, Path diffTar) throws IOException {
        ArchiveBaseline baseline = new ArchiveBaseline(fullTar);
        TarIndex index = null;
        ArchiveRestorePlan restorePlan = null;
        try {
            index = diffTar == null ? null : TarIndex.read(diffTar);
            restorePlan = ArchiveRestorePlan.open(baseline,
                    index == null ? baseline.meta() : index.snapshot("diff"), index);
        } catch (IOException | RuntimeException | Error failure) {
            if (restorePlan != null) try { restorePlan.close(); } catch (IOException ignored) { }
            try { baseline.close(); } catch (IOException ignored) { }
            throw failure;
        }
        this.base = baseline;
        this.diff = index;
        this.plan = restorePlan;

        List<RestoreEntry> regions = new ArrayList<>(plan.regions.size());
        List<RestoreEntry> all = new ArrayList<>();
        for (ArchiveRestorePlan.Region region : plan.regions) {
            RestoreEntry entry = RestoreEntry.region(region.path(), region.rx(), region.rz(),
                    region.contentHash(), region.sizeUncompressed(), region.sourceMetadata());
            regions.add(entry);
            all.add(entry);
        }
        for (ArchiveRestorePlan.FileRef file : plan.files.values())
            all.add(RestoreEntry.file(file.path(), file.sourceHash(), file.sizeUncompressed(),
                    file.sourceMetadata()));
        this.regionEntries = List.copyOf(regions);
        this.entries = List.copyOf(all);
    }

    @Override
    public List<RestoreEntry> entries() {
        return entries;
    }

    @Override
    public InputStream openFile(RestoreEntry entry) throws IOException {
        ArchiveRestorePlan.FileRef file = plan.files.get(entry.path());
        if (file == null) throw new IOException("Missing archive file entry: " + entry.path());
        return new ZstdInputStream(file.source().open());
    }

    @Override
    public void readRegions(List<RestoreEntry> regions, RegionConsumer consumer, int threads) throws IOException {
        if (regions.isEmpty()) return;
        Set<String> paths = regions.stream().map(RestoreEntry::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        plan.runRegionsSelected(paths, ArchiveTasks.TaskWork.decompress(), threads, null,
                (index, source) -> {
                    consumer.accept(regionEntries.get(index), source);
                    return Boolean.TRUE;
                });
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            plan.close();
        } catch (IOException e) {
            failure = e;
        }
        try { base.close(); } catch (IOException e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
