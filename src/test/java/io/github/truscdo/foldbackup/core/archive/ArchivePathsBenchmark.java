package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import java.nio.file.*;
import java.util.*;

/** Compares bounded single-worker and parallel archive readers/importers. */
final class ArchivePathsBenchmark {
    static void run(Path output, List<RegionEntry> regions, StringBuilder report) throws Exception {
        Path archive = output.resolve("archive-path-input");
        Files.createDirectories(archive);
        ArchiveSnapshot full = new ArchiveSnapshot();
        full.type = "full"; full.sourceSnapshotId = "S-20260914_120000"; full.epoch = 1789387200L;
        full.world = "benchmark"; full.regionCount = regions.size();
        for (var region : regions) full.regions.add(new ArchiveSnapshot.RegionInfo(region.getFilename(),
                region.getSizeUncompressed(), region.getContentHash(), region.getSourceMetadata()));
        try (var writer = new TarWriter(Files.newOutputStream(archive.resolve(ArchiveCreator.fullTarName(full.sourceSnapshotId))));
                var input = new TarReader(Files.newInputStream(output.resolve("streaming.tar")))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(full));
            TarReader.Entry entry;
            while ((entry = input.nextEntry()) != null) writer.putEntry(entry.name, input.entryStream(), entry.size);
        }
        ArchiveSnapshot diff = new ArchiveSnapshot();
        diff.type = "diff"; diff.sourceSnapshotId = "S-20260914_130000"; diff.epoch = full.epoch + 3600;
        diff.baseEpoch = full.epoch; diff.world = full.world; diff.regionCount = full.regionCount;
        diff.regions.addAll(full.regions);
        try (var writer = new TarWriter(Files.newOutputStream(archive.resolve(
                ArchiveCreator.diffTarName(full.sourceSnapshotId, diff.sourceSnapshotId))))) {
            writer.putEntry("snapshot.bin", ArchiveSnapshotCodec.encode(diff));
        }
        report.append("restore/import sample: same regions, unchanged diff; warm-cache runs, 2 parallel workers\n");
        for (int round = 0; round < 2; round++) {
            for (boolean parallel : round == 0 ? new boolean[] {false, true} : new boolean[] {true, false}) {
                String mode = parallel ? "parallel" : "single-worker";
                Path target = output.resolve("paths-" + UUID.randomUUID());
                for (boolean delta : new boolean[] {false, true}) {
                    Path world = target.resolve(delta ? "diff" : "full");
                    long start = System.nanoTime();
                    var metrics = parallel ? (java.util.function.Consumer<ArchiveTasks.Metrics>) value ->
                            report.append("restore tasks: ").append(value).append('\n') : null;
                    new ArchiveRestorer(6, parallel ? 2 : 1, metrics).restore(archive, full.epoch,
                            delta ? diff.epoch : null, world);
                    row(report, round, mode, delta ? "restore-diff" : "restore-full", start);
                    for (var region : regions) {
                        var actual = RegionStream.inspect(McaStream.source(world.resolve(region.getFilename())));
                        var expected = LrhFormat.read(output.resolve(region.getBlobPath()), new RegionStream.Accumulator());
                        if (!actual.same(expected)) throw new AssertionError("Restored content mismatch");
                    }
                }
                Path store = target.resolve("store");
                long start = System.nanoTime();
                var metrics = parallel ? (java.util.function.Consumer<ArchiveTasks.Metrics>) value ->
                        report.append("import tasks: ").append(value).append('\n') : null;
                int imported = new ArchiveImporter(archive, store, parallel ? 2 : 1, metrics).importAll();
                row(report, round, mode, "import-full-and-diff", start);
                if (imported != 2) throw new AssertionError("Missing imported snapshots");
                var snapshots = new SnapshotStore(store);
                for (ArchiveSnapshot archiveMeta : List.of(full, diff)) {
                    var manifest = snapshots.loadSnapshot(archiveMeta.epoch);
                    Path world = target.resolve(archiveMeta.isFull() ? "full" : "diff");
                    for (var region : manifest.getRegions()) {
                        Path mca = world.resolve(region.getFilename());
                        String contentHash = io.github.truscdo.foldbackup.core.HashUtil.bytesToHex(
                                RegionStream.inspect(McaStream.source(mca)).hash());
                        if (!contentHash.equals(region.getContentHash()))
                            throw new AssertionError("Imported content identity mismatch");
                        var actual = LrhFormat.read(store.resolve(region.getBlobPath()), new RegionStream.Accumulator());
                        if (!actual.same(RegionStream.inspect(McaStream.source(mca))))
                            throw new AssertionError("Imported content mismatch");
                    }
                }
            }
        }
    }
    private static void row(StringBuilder report, int round, String mode, String path, long start) {
        String row = String.format(Locale.ROOT, "round=%d mode=%s path=%s seconds=%.3f%n",
                round + 1, mode, path, (System.nanoTime() - start) / 1e9);
        report.append(row); System.out.print(row);
    }
}
