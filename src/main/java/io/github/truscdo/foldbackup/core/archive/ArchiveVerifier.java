package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.ProgressListener;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.LrhdFormat;
import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Archive verifier: checks snapshot.bin, every {@code .lrh} frame and
 * contentHash, every {@code .lrhd} chunk hash, and files.bin.
 * <p>
 * Full-tar {@code .lrh} entries are validated by streaming them through
 * {@link LrhFormat#read} into a
 * {@link io.github.truscdo.foldbackup.core.format.RegionStream.Accumulator}, which re-derives
 * the length table and content hash without materialising a region or a
 * payload array.
 */
public class ArchiveVerifier {

    /** Verification outcome. */
    public static final class Result {
        public final List<String> errors = new ArrayList<>();
        public int lrhChecked;
        public int lrhdChecked;
        public int chunkHashesChecked;
        public long totalBytes;

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Verify a full archive (and optionally its diff).
     *
     * @param archiveRoot archive directory
     * @param baseEpochSeconds full archive identity, or null for the latest full archive
     * @param diffEpochSeconds diff archive identity, or null for full-only
     */
    public Result verify(Path archiveRoot, Long baseEpochSeconds, Long diffEpochSeconds) throws IOException {
        return verify(archiveRoot, baseEpochSeconds, diffEpochSeconds, null);
    }

    /** Verify with the common structured progress protocol. */
    public Result verify(Path archiveRoot, Long baseEpochSeconds, Long diffEpochSeconds,
            ProgressListener listener) throws IOException {
        Result result = new Result();
        var progress = new ProgressTracker(listener);
        if (!Files.isDirectory(archiveRoot)) {
            throw new IOException("Archive root not found: " + archiveRoot);
        }

        progress.stage("Locating backups", diffEpochSeconds == null ? 1 : 2);
        Path fullTar = locateFullTar(archiveRoot, baseEpochSeconds);
        progress.done(fullTar.getFileName().toString());
        Path diffTar = null;
        if (diffEpochSeconds != null) {
            diffTar = locateDiffTar(archiveRoot, baseEpochSeconds, diffEpochSeconds);
            progress.done(diffTar.getFileName().toString());
        }
        verifyFullTar(fullTar, result, progress);
        if (diffTar != null) {
            verifyDiffTar(fullTar, diffTar, result, progress);
        }
        return result;
    }

    /** Verify the selected archive and, for a diff, its required full baseline. */
    public Result verifyArchive(Path archiveRoot, Path archiveFile) throws IOException {
        return verifyArchive(archiveRoot, archiveFile, null);
    }

    public Result verifyArchive(Path archiveRoot, Path archiveFile, ProgressListener listener) throws IOException {
        Result result = new Result();
        var progress = new ProgressTracker(listener);
        if (!Files.isDirectory(archiveRoot)) {
            result.errors.add("Archive root not found: " + archiveRoot);
            return result;
        }
        if (!Files.isRegularFile(archiveFile)) {
            result.errors.add(archiveFile.getFileName() + ": archive file not found");
            return result;
        }
        ArchiveSnapshot meta = ArchiveFormatUtil.readSnapshot(archiveFile);
        if (meta == null) {
            result.errors.add(archiveFile.getFileName() + ": cannot read snapshot.bin or archive is damaged");
            return result;
        }
        if (meta.isFull()) {
            Result full = new Result();
            try {
                verifyFullTar(archiveFile, full, progress);
            } catch (IOException | RuntimeException e) {
                full.errors.add(e.getMessage());
            }
            mergeResult(result, full, archiveFile.getFileName().toString());
        } else if (meta.isDiff()) {
            Path fullTar;
            try {
                fullTar = ArchiveTarScanner.fullAtEpoch(ArchiveTarScanner.scan(archiveRoot), meta.baseEpoch);
            } catch (IOException e) {
                String expected = ArchiveCreator.fullTarName(SnapshotStore.generateSnapshotId(meta.baseEpoch));
                result.errors.add(archiveFile.getFileName() + ": required baseline archive "
                        + expected + " is missing: " + e.getMessage());
                return result;
            }
            Result baseline = new Result();
            try {
                verifyFullTar(fullTar, baseline, progress);
            } catch (IOException | RuntimeException e) {
                baseline.errors.add(e.getMessage());
            }
            mergeResult(result, baseline, fullTar.getFileName().toString());

            Result diff = new Result();
            try {
                verifyDiffTar(fullTar, archiveFile, diff, progress);
            } catch (IOException | RuntimeException e) {
                diff.errors.add(e.getMessage());
            }
            mergeResult(result, diff, archiveFile.getFileName().toString());
        } else {
            result.errors.add(archiveFile.getFileName() + ": unsupported archive type " + meta.type);
        }
        return result;
    }

    private static void mergeResult(Result target, Result source, String archiveName) {
        target.lrhChecked += source.lrhChecked;
        target.lrhdChecked += source.lrhdChecked;
        target.chunkHashesChecked += source.chunkHashesChecked;
        target.totalBytes += source.totalBytes;
        for (String error : source.errors) target.errors.add(archiveName + ": " + error);
    }

    /** Verify every tar in the archive root, continuing after individual failures. */
    public Result verifyAll(Path archiveRoot, ProgressListener listener) throws IOException {
        Result combined = new Result();
        if (!Files.isDirectory(archiveRoot)) throw new IOException("Archive root not found: " + archiveRoot);
        List<Path> archives;
        try (var stream = Files.list(archiveRoot)) {
            archives = stream.filter(path -> path.getFileName().toString().endsWith(".tar"))
                    .sorted().toList();
        }
        var progress = new ProgressTracker(listener);
        progress.stage("Verifying archives", archives.size());
        for (Path archive : archives) {
            progress.current(archive.getFileName().toString());
            try {
                Result one = verifyArchive(archiveRoot, archive);
                combined.lrhChecked += one.lrhChecked;
                combined.lrhdChecked += one.lrhdChecked;
                combined.chunkHashesChecked += one.chunkHashesChecked;
                combined.totalBytes += one.totalBytes;
                for (String error : one.errors)
                    combined.errors.add(error.startsWith(archive.getFileName() + ":")
                            ? error : archive.getFileName() + ": " + error);
            } catch (IOException | RuntimeException e) {
                combined.errors.add(archive.getFileName() + ": " + e.getMessage());
            } finally {
                progress.done(archive.getFileName().toString());
            }
        }
        return combined;
    }

    // ── full tar ──

    private void verifyFullTar(Path fullTar, Result result, ProgressTracker progress) throws IOException {
        progress.stage("Verifying " + fullTar.getFileName(), -1);
        TarIndex archive = TarIndex.read(fullTar);
        ArchiveSnapshot meta = archive.snapshot("full");
        Set<String> regions = new java.util.HashSet<>();
        for (var region : meta.regions) {
            if (!regions.add(region.contentHash)) continue;
            String name = ArchiveCreator.regionObjectPath(region.contentHash);
            TarIndex.Entry entry = archive.entries.get(name);
            progress.current(name);
            if (entry == null) result.errors.add(name + ": referenced object is missing");
            else verifyRegionObject(entry, region.contentHash, name, result);
            progress.done(name);
        }
        Set<String> files = new java.util.HashSet<>();
        for (var file : meta.files) {
            if (!files.add(file.sourceHash)) continue;
            String name = ArchiveCreator.fileObjectPath(file.sourceHash);
            TarIndex.Entry entry = archive.entries.get(name);
            progress.current(name);
            if (entry == null) result.errors.add(name + ": referenced object is missing");
            else verifyFileObject(entry, file.sourceHash, file.sizeUncompressed, name, result);
            progress.done(name);
        }
        try (var ignored = ArchiveRestorePlan.open(fullTar, null)) { }
    }

    // ── diff tar ──

    private void verifyDiffTar(Path fullTar, Path diffTar, Result result, ProgressTracker progress) throws IOException {
        progress.stage("Verifying " + diffTar.getFileName(), -1);
        TarIndex archive = TarIndex.read(diffTar);
        ArchiveSnapshot meta = archive.snapshot("diff");
        ArchiveSnapshot base = TarIndex.read(fullTar).snapshot("full");
        Map<String, Long> targetSizes = new java.util.HashMap<>();
        for (var file : meta.files) targetSizes.put(file.sourceHash, file.sizeUncompressed);
        Set<String> baseHashes = new java.util.HashSet<>();
        for (var file : base.files) baseHashes.add(file.sourceHash);
        for (var entry : archive.entries.values()) {
            String name = entry.name();
            progress.current(name);
            if (name.endsWith("diff.lrhd")) {
                try (InputStream input = entry.open()) { verifyLrhd(input, entry.size(), name, result); }
            } else if (name.equals("files.bin")) {
                var manifest = FilesManifestCodec.decode(entry.bytes());
                if (manifest.baseEpoch != base.epoch) result.errors.add(name + ": baseline mismatch");
            } else if (name.startsWith("objects/files/")) {
                String hash = name.substring("objects/files/".length(), name.length() - ".zst".length());
                Long size = targetSizes.get(hash);
                if (size == null) result.errors.add(name + ": object is not referenced by target manifest");
                else verifyFileObject(entry, hash, size, name, result);
            }
            progress.done(name);
        }
        for (var file : meta.files) {
            if (!baseHashes.contains(file.sourceHash)
                    && !archive.entries.containsKey(ArchiveCreator.fileObjectPath(file.sourceHash)))
                result.errors.add(file.filename + ": changed file object is missing");
        }
        try (var plan = ArchiveRestorePlan.open(fullTar, diffTar)) {
            progress.stage("Reconstructing diff regions", plan.regions.size());
            plan.runRegions(ArchiveDefaults.RESTORE_LEVEL, 0, null,
                    index -> progress.done(plan.regions.get(index).path()), (index, source) -> {
                        var region = plan.regions.get(index);
                        var accumulator = new io.github.truscdo.foldbackup.core.format.RegionStream.Accumulator();
                        source.read(accumulator);
                        String actualHash = HashUtil.bytesToHex(accumulator.finish().hash());
                        if (!region.contentHash().equals(actualHash))
                            throw new IOException("Merged Region identity mismatch: " + region.path());
                        return Boolean.TRUE;
                    });
        }
    }

    private void verifyRegionObject(TarIndex.Entry entry, String expectedHash, String name, Result result) {
        result.totalBytes += entry.size();
        result.lrhChecked++;
        try (InputStream input = entry.open()) {
            var summary = LrhFormat.read(input, entry.size(), new io.github.truscdo.foldbackup.core.format.RegionStream.Accumulator());
            if (!expectedHash.equals(HashUtil.bytesToHex(summary.hash())))
                throw new IOException("object path does not match Region content identity");
        } catch (Exception failure) { result.errors.add(name + ": " + failure.getMessage()); }
    }

    private void verifyFileObject(TarIndex.Entry entry, String expectedHash, long expectedSize,
            String name, Result result) {
        result.totalBytes += entry.size();
        try (InputStream raw = entry.open();
                InputStream input = new com.github.luben.zstd.ZstdInputStream(raw)) {
            if (!FileContentIdentity.inspect(input).matches(expectedSize, expectedHash))
                throw new IOException("object path does not match file content identity");
        } catch (Exception failure) { result.errors.add(name + ": " + failure.getMessage()); }
    }

    private void verifyLrhd(InputStream input, long size, String name, Result result) {
        result.lrhdChecked++;
        try {
            var index = LrhdFormat.verify(input, size);
            result.chunkHashesChecked += index.header().changedChunkCount();
        } catch (Exception ex) {
            result.errors.add(name + ": " + ex.getMessage());
        }
    }

    // ── tar location (mirrors ArchiveRestorer) ──

    private Path locateFullTar(Path archiveRoot, Long baseEpochSeconds) throws IOException {
        List<ArchiveTarScanner.ArchiveTarInfo> tars = ArchiveTarScanner.scan(archiveRoot);
        if (baseEpochSeconds != null) return ArchiveTarScanner.fullAtEpoch(tars, baseEpochSeconds);
        List<ArchiveTarScanner.ArchiveTarInfo> fulls = ArchiveTarScanner.fulls(tars);
        if (fulls.isEmpty()) throw new IOException("No full archive found in " + archiveRoot);
        return ArchiveTarScanner.fullAtEpoch(tars, fulls.getFirst().meta().epoch);
    }

    private Path locateDiffTar(Path archiveRoot, Long baseEpochSeconds, long diffEpochSeconds) throws IOException {
        if (baseEpochSeconds == null) {
            throw new IOException("--base is required when --diff is specified");
        }
        return ArchiveTarScanner.diffAtEpoch(ArchiveTarScanner.scan(archiveRoot), baseEpochSeconds, diffEpochSeconds);
    }
}
