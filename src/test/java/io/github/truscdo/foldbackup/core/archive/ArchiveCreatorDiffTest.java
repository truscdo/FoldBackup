package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.*;
import io.github.truscdo.foldbackup.core.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveCreatorDiffTest {
    @TempDir Path temp;

    @Test void singleDirectoryMetadataLargerThanHeapIsSpilledAndStreamed() throws Exception {
        Path log = temp.resolve("memory.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("foldbackup.testClasspath"),
                DiffPlanMemoryProcess.class.getName(), temp.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "Diff plan memory probe timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("DIFF_PLAN_MEMORY_OK"), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private RegionEntry region(BlobStore blobs, String path, String value) throws IOException {
        try (var session = blobs.newWriteSession()) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var summary = LrhFormat.write(session.channel(), consumer -> {
                if (bytes.length > 0) {
                    consumer.begin(0, -1);
                    consumer.data(bytes, 0, bytes.length);
                    consumer.end();
                }
            }, 1);
            String hash = HashUtil.bytesToHex(summary.hash());
            var commit = session.commit(hash, "lrh");
            return new RegionEntry(path, hash, commit.path(), commit.size(), summary.nbtBytes());
        }
    }

    private Map<String, byte[]> entries(Path archive) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (var input = Files.newInputStream(archive); var reader = new TarReader(input)) {
            TarReader.Entry entry;
            while ((entry = reader.nextEntry()) != null) entries.put(entry.name, reader.readEntryData());
        }
        return entries;
    }

    @Test void parallelAndSerialDiffEntriesMatchIncludingDeletionsAndSignedTimestamps() throws Exception {
        var blobs = new BlobStore(temp.resolve("store"), 1);
        var store = new SnapshotStore(blobs.getRootDir());
        var base = new SnapshotManifest("S-20260917_100000", 1000, "world");
        var target = new SnapshotManifest("S-20260917_110000", 2000, "world");
        for (int dir = 0; dir < 6; dir++) {
            // Deliberately noncanonical manifest order, including negative coordinates.
            for (int rx : new int[]{2, -1, 0}) {
                String path = "dim" + dir + "/region/r." + rx + ".0.mca";
                base.addRegion(region(blobs, path, "base-" + rx));
            }
        }
        base.addRegion(region(blobs, "deleted/r.0.0.mca", "deleted"));
        store.saveSnapshot(base);
        for (int dir = 0; dir < 6; dir++) for (int rx : new int[]{2, -1, 0}) {
            String path = "dim" + dir + "/region/r." + rx + ".0.mca";
            target.addRegion(region(blobs, path, dir == 0 ? "" : "target-" + dir + "-" + rx));
        }
        target.addRegion(region(blobs, "new/r.0.0.mca", "new"));
        store.saveSnapshot(target);
        var exporter = new ArchiveCreator(store, blobs, 1);
        Map<String, byte[]> serial = entries(exporter.createDiff(base.getEpochSeconds(), target.getEpochSeconds(),
                temp.resolve("serial"), 1));
        Thread caller = Thread.currentThread();
        Map<String, byte[]> parallel = entries(exporter.createDiffWithProgress(base.getEpochSeconds(), target.getEpochSeconds(),
                temp.resolve("parallel"), 4, event -> assertSame(caller, Thread.currentThread())));
        assertEquals(serial.keySet(), parallel.keySet());
        for (String name : serial.keySet()) assertArrayEquals(serial.get(name), parallel.get(name), name);
        Path cliOutput = temp.resolve("cli");
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(messages));
            io.github.truscdo.foldbackup.cli.ArchiveCli.runArchiveCreate(blobs.getRootDir(), cliOutput, java.util.List.of(
                    "archive", "create", "--snapshot", target.getSnapshotId(), "--base", base.getSnapshotId(),
                    "--level", "1", "--threads", "2"));
        } finally { System.setOut(previous); }
        assertFalse(messages.toString().contains("ignored for diff"));
        var cli = entries(cliOutput.resolve(ArchiveCreator.diffTarName(base.getSnapshotId(), target.getSnapshotId())));
        assertEquals(serial.keySet(), cli.keySet());
        for (String name : serial.keySet()) assertArrayEquals(serial.get(name), cli.get(name), name);
        assertTrue(parallel.containsKey("files.bin"));
        for (var entry : parallel.entrySet()) if (entry.getKey().endsWith(".lrhd")) {
            var decoded = LrhdTestSupport.read(entry.getValue());
            assertFalse(decoded.allChunks().isEmpty());
            for (var chunk : decoded.allChunks()) {
                if (entry.getKey().startsWith("dim0/")) assertTrue(chunk.deleted);
                else assertEquals(-1L, chunk.ts);
            }
        }
    }

    @Test void existingArchiveIsRejectedBeforeComparisonAndPreserved() throws Exception {
        var blobs = new BlobStore(temp.resolve("store"), 1);
        var store = new SnapshotStore(blobs.getRootDir());
        var base = new SnapshotManifest("S-20260917_100000", 1000, "world");
        var target = new SnapshotManifest("S-20260917_110000", 2000, "world");
        store.saveSnapshot(base);
        var entry = region(blobs, "region/r.0.0.mca", "target");
        target.addRegion(entry);
        store.saveSnapshot(target);
        Path output = temp.resolve("archive");
        Files.createDirectories(output);
        Path committed = output.resolve(ArchiveCreator.diffTarName(base.getSnapshotId(), target.getSnapshotId()));
        Files.writeString(committed, "previous archive");
        var comparisonStarted = new AtomicBoolean();
        IOException failure = assertThrows(IOException.class, () -> new ArchiveCreator(store, blobs, 1).createDiffWithProgress(
                base.getEpochSeconds(), target.getEpochSeconds(), output, 4, event -> {
                    if (event.phase().equals("Comparing regions")) comparisonStarted.set(true);
                }));
        assertTrue(failure.getMessage().contains("Archive already exists"), failure.toString());
        assertFalse(comparisonStarted.get(), "existing archive is rejected before comparison");
        assertEquals("previous archive", Files.readString(committed));
        try (var paths = Files.list(output)) { assertEquals(1, paths.count()); }
    }

    @Test void cancellationBeforeArchiveAndPlanSpoolFailureCleanUp() throws Exception {
        var blobs = new BlobStore(temp.resolve("store"), 1);
        var store = new SnapshotStore(blobs.getRootDir());
        var base = new SnapshotManifest("S-20260917_100000", 1000, "world");
        var target = new SnapshotManifest("S-20260917_110000", 2000, "world");
        store.saveSnapshot(base);
        target.addRegion(region(blobs, "region/r.0.0.mca", "target"));
        store.saveSnapshot(target);
        Path output = temp.resolve("archive");
        try {
            assertThrows(IOException.class, () -> new ArchiveCreator(store, blobs, 1).createDiffWithProgress(
                    base.getEpochSeconds(), target.getEpochSeconds(), output, 4, event -> {
                        if (event.phase().equals("Comparing regions")) Thread.currentThread().interrupt();
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        try (var paths = Files.list(output)) { assertEquals(0, paths.count()); }

        Path scratch;
        try (var directory = new ArchiveTemporaryDirectory()) {
            scratch = directory.path;
            try (var diff = new StreamingDiff(blobs, scratch)) {
                diff.close(); // Simulate a plan spool becoming unavailable during collection.
                assertThrows(IOException.class, () -> diff.compare(base, target, 4, new ProgressTracker(null)));
            }
        }
        assertFalse(Files.exists(scratch));
    }

    @Test void separateBudgetsClampCpuMemoryAndSubmissionWindow() {
        assertEquals(1, DiffResourceBudget.scanWorkers(100, 100, 1));
        assertEquals(1, DiffResourceBudget.scanWorkers(100, 1, Long.MAX_VALUE));
        var tiny = DiffResourceBudget.compression(100, 100, 19, 1);
        assertEquals(1, tiny.workers());
        assertEquals(2L * DiffResourceBudget.BLOCK_SIZE, tiny.bufferBytes());
        var roomy = DiffResourceBudget.compression(100, 100, 19, Long.MAX_VALUE);
        assertTrue(roomy.workers() <= Runtime.getRuntime().availableProcessors());
        assertTrue(roomy.window() <= 2L * roomy.workers());
        assertEquals(64L << 20, roomy.bufferBytes());
    }
}
