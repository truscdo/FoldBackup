package io.github.truscdo.foldbackup.core.backup;

import io.github.truscdo.foldbackup.core.rollback.StreamingRollbackMemoryProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StreamingMemoryTest {
    @TempDir Path temp;
    @Test void chunkRollbackRegionLargerThanHeapCompletes() throws Exception {
        Path log = temp.resolve("rollback-memory.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("foldbackup.testClasspath"),
                StreamingRollbackMemoryProcess.class.getName(), temp.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Rollback probe timed out");
            String output = Files.readString(log); System.out.println(output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("STREAM_ROLLBACK_OK region_mib=96"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    @Test void rollbackSelectionAndDeltaRestoreLargerThanHeapComplete() throws Exception {
        Path log = temp.resolve("restore-memory.log");
        Path scratch = Files.createDirectory(temp.resolve("scratch"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-Djava.io.tmpdir=" + scratch, "-cp", System.getProperty("foldbackup.testClasspath"),
                StreamingRestoreMemoryProcess.class.getName(), temp.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Restore probe timed out");
            String output = Files.readString(log); System.out.println(output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("STREAM_RESTORE_OK mib=96"), output);
            try (var files = Files.list(scratch)) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("foldbackup-restore-")),
                        "Restore spools must be cleaned up");
            }
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
    @Test void fileAndSingleChunkLargerThanHeapComplete() throws Exception {
        Path log=temp.resolve("memory.log");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx32m","-cp",System.getProperty("foldbackup.testClasspath"),StreamingMemoryProcess.class.getName(),temp.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Low-heap probe timed out");
            String output=Files.readString(log); System.out.println(output);
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("STREAM_MEMORY_OK mib=96"),output);
        } finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); } }
    }
}
