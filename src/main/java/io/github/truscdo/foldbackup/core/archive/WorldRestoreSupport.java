package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.HashUtil;
import io.github.truscdo.foldbackup.core.ProgressTracker;
import io.github.truscdo.foldbackup.core.format.RegionStream;
import io.github.truscdo.foldbackup.core.format.StreamIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Common verification and publication steps for archive and snapshot world restores. */
final class WorldRestoreSupport {
    private WorldRestoreSupport() {}

    static RegionStream.Source verifiedSource(String path, String expectedHash, RegionStream.Source source) {
        return output -> {
            var check = new RegionStream.Accumulator();
            source.read(new RegionStream.Consumer() {
                @Override public void begin(int index, int timestamp) throws IOException {
                    check.begin(index, timestamp);
                    output.begin(index, timestamp);
                }
                @Override public void present(boolean present) throws IOException {
                    check.present(present);
                    output.present(present);
                }
                @Override public void data(byte[] bytes, int offset, int length) throws IOException {
                    check.data(bytes, offset, length);
                    output.data(bytes, offset, length);
                }
                @Override public void end() throws IOException {
                    check.end();
                    output.end();
                }
            });
            if (!expectedHash.equals(HashUtil.bytesToHex(check.finish().hash())))
                throw new IOException("Restored Region identity mismatch: " + path);
        };
    }

    static void writeFile(Path root, String path, long expectedSize, String expectedHash, InputStream data)
            throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        FileContentIdentity.Summary summary;
        try (var output = Files.newOutputStream(target)) {
            summary = FileContentIdentity.copy(data, output);
        }
        if (!summary.matches(expectedSize, expectedHash))
            throw new IOException("Restored file identity mismatch: " + path);
    }

    static void publish(Path stage, Path outputDir, ProgressTracker progress) throws IOException {
        StreamIo.checkCancelled();
        Files.createDirectories(outputDir);
        try (var walk = Files.walk(stage)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            progress.stage("Publishing world", files.size());
            for (Path source : files) {
                StreamIo.checkCancelled();
                Path relative = stage.relativize(source);
                Path target = outputDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                progress.done(relative.toString());
            }
        }
    }
}
