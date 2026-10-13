package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.AnvilFormat;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.McaStreamWriter;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Restores an online snapshot using the archive world's staged, verified Region workflow. */
public final class SnapshotWorldRestorer {
    private record Region(RegionEntry entry, int rx, int rz) {}

    private final BlobStore blobs;
    private final int compressionLevel;
    private final int threads;

    public SnapshotWorldRestorer(BlobStore blobs, int compressionLevel, int threads) {
        if (threads < 0) throw new IllegalArgumentException("Negative worker count");
        this.blobs = blobs;
        this.compressionLevel = compressionLevel;
        this.threads = threads;
    }

    public Path restore(SnapshotManifest snapshot, Path outputDir, ProgressListener listener) throws IOException {
        List<RegionEntry> sourceRegions = snapshot.getRegions() == null ? List.of() : snapshot.getRegions();
        List<FileEntry> files = snapshot.getExtraFiles() == null ? List.of() : snapshot.getExtraFiles();
        var regions = new java.util.ArrayList<Region>(sourceRegions.size());
        var targets = new WorldRestoreTargets();
        for (RegionEntry entry : sourceRegions) {
            TarIndex.validatePath(entry.getFilename());
            int slash = entry.getFilename().lastIndexOf('/');
            String name = entry.getFilename().substring(slash + 1);
            if (!name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                throw new IOException("Invalid region filename: " + entry.getFilename());
            int[] coords;
            try { coords = AnvilFormat.parseRegionCoords(name); }
            catch (NumberFormatException e) { throw new IOException("Invalid region coordinates: " + name, e); }
            regions.add(new Region(entry, coords[0], coords[1]));
            targets.addRegion(entry.getFilename(), coords[0], coords[1], "Conflicting output path: ");
        }
        for (FileEntry file : files) {
            TarIndex.validatePath(file.getFilename());
            targets.addFile(file.getFilename());
        }
        targets.validateParents();

        var progress = new ProgressTracker(listener);
        try (var staged = new ArchiveTemporaryDirectory()) {
            progress.stage("Restoring files", files.size());
            for (FileEntry file : files) {
                progress.current(file.getFilename());
                try (var input = blobs.openFile(file.getBlobPath())) {
                    WorldRestoreSupport.writeFile(staged.path, file.getFilename(), file.getSizeUncompressed(),
                            file.getSourceHash(), input);
                }
                progress.done(file.getFilename());
            }

            progress.stage("Restoring regions", regions.size());
            // 源帧级别决定解码窗口；输出侧是 zlib（McaStreamWriter），相对解码窗口可忽略。
            int sourceFrameLevel = ArchiveTasks.maxSourceFrameLevel(
                    regions.stream().map(r -> blobs.getRootDir().resolve(r.entry().getBlobPath())).toList());
            ArchiveTasks.run(regions.size(),
                    ArchiveTasks.decompressQuota(regions.size(), sourceFrameLevel, threads), 2,
                    index -> {
                        Region region = regions.get(index);
                        RegionEntry entry = region.entry();
                        McaStreamWriter.write(staged.path.resolve(entry.getFilename()), region.rx(), region.rz(), 0,
                                compressionLevel, WorldRestoreSupport.verifiedSource(entry.getFilename(),
                                        entry.getContentHash(), consumer -> LrhFormat.read(
                                                blobs.getRootDir().resolve(entry.getBlobPath()), consumer)));
                        return Boolean.TRUE;
                    }, index -> progress.done(regions.get(index).entry().getFilename()), null);

            WorldRestoreSupport.publish(staged.path, outputDir, progress);
        }
        return outputDir;
    }
}
