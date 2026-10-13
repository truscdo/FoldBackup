package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import io.github.truscdo.foldbackup.core.HashUtil;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static java.nio.file.StandardOpenOption.*;

/** Manual real-world sample benchmark; args: world.zip output-directory [region-count]. */
public final class ArchivePipelineBenchmark {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        var regions = new ArrayList<RegionEntry>();
        long nbtBytes = 0;
        try (var zip = new ZipFile(args[0])) {
            var selected = zip.stream().filter(e -> e.getName().startsWith("region/")
                    && e.getName().endsWith(".mca") && e.getSize() >= (1L << 20))
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).limit(limit).toList();
            // External chunks may be referenced by the sampled MCA files.
            for (var entry : zip.stream().filter(e -> e.getName().startsWith("region/")
                    && e.getName().endsWith(".mcc")).toList()) {
                Path target = output.resolve("source").resolve(Path.of(entry.getName()).getFileName());
                Files.createDirectories(target.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
            }
            for (var entry : selected) {
                Path source = output.resolve("source").resolve(Path.of(entry.getName()).getFileName());
                Files.createDirectories(source.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING); }
                String blob = "source-" + regions.size() + ".lrh";
                String contentHash;
                try (var channel = FileChannel.open(output.resolve(blob), CREATE, WRITE, TRUNCATE_EXISTING)) {
                    var summary = LrhFormat.write(channel, McaStream.source(source), 6);
                    nbtBytes += summary.nbtBytes();
                    contentHash = HashUtil.bytesToHex(summary.hash());
                }
                regions.add(new RegionEntry(entry.getName(), contentHash, blob,
                        Files.size(output.resolve(blob)), entry.getSize()));
            }
        }
        if (regions.isEmpty()) throw new IllegalArgumentException("No suitable region samples");
        var exporter = new ArchiveCreator(new SnapshotStore(output), new BlobStore(output, 6), 19);
        var report = new StringBuilder("regions=" + regions.size() + ", nbtBytes=" + nbtBytes + "\n");
        // Warm up native codec and filesystem paths outside measured runs.
        try (var channel = FileChannel.open(output.resolve("warmup.lrh"), CREATE, WRITE, TRUNCATE_EXISTING)) {
            LrhFormat.recompress(output.resolve(regions.getFirst().getBlobPath()), channel, 19);
        }
        for (int round = 0; round < 2; round++) {
            Path archive = output.resolve("streaming.tar");
            long start = System.nanoTime();
            OrderedCompressionPipeline.Metrics metrics;
            try (var channel = FileChannel.open(archive, CREATE, WRITE, TRUNCATE_EXISTING);
                    var tar = new TarWriter(channel)) {
                var plan = exporter.planRegions(regions, 1L << 30, 19, 2);
                metrics = exporter.exportRegionsParallel(tar, regions, plan.quota(), plan.bufferBytes(), 19,
                        new io.github.truscdo.foldbackup.core.ProgressTracker(null));
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            String row = String.format(Locale.ROOT,
                    "round=%d mode=streaming seconds=%.3f NBT-MiB/s=%.3f archiveBytes=%d metrics=%s%n",
                    round + 1, seconds, nbtBytes / (double) (1 << 20) / seconds, Files.size(archive), metrics);
            report.append(row);
            System.out.print(row);
            validateArchive(archive, output, regions);
        }
        report.append("streaming archive validated by content identity: LRH hashes, lengths and timestamps match\n");
        ArchivePathsBenchmark.run(output, regions, report);
        Files.writeString(output.resolve("results.txt"), report);
        System.out.print(report);
    }

    private static void validateArchive(Path archive, Path root, List<RegionEntry> regions) throws Exception {
        var expected = new HashMap<String, RegionEntry>();
        for (var region : regions) expected.put(ArchiveCreator.regionObjectPath(region.getContentHash()), region);
        try (var tar = new TarReader(Files.newInputStream(archive))) {
            TarReader.Entry entry;
            while ((entry = tar.nextEntry()) != null) {
                var region = expected.remove(entry.name);
                if (region == null) throw new AssertionError("Unexpected/duplicate entry: " + entry.name);
                var actual = LrhFormat.read(tar.entryStream(), entry.size, new RegionStream.Accumulator());
                var original = LrhFormat.read(root.resolve(region.getBlobPath()), new RegionStream.Accumulator());
                if (!actual.same(original)) throw new AssertionError("Summary mismatch: " + entry.name);
            }
        }
        if (!expected.isEmpty()) throw new AssertionError("Missing regions");
    }

}
