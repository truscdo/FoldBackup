package io.github.truscdo.foldbackup.core.format;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hashes regular file bytes and locates external chunk files for a Region. */
public final class McaHasher {
    private static final Pattern MCC_PATTERN = Pattern.compile("^c\\.(-?\\d+)\\.(-?\\d+)\\.mcc$");

    private McaHasher() {}

    /** XXH3-128 of a regular file's original bytes. */
    public static String hashFile(Path filePath) throws IOException {
        return inspectFile(filePath).hash();
    }

    /** Content identity and length from the same read, independent of later file changes. */
    public record FileHash(String hash, long length) {}

    public static FileHash inspectFile(Path filePath) throws IOException {
        var hash = new io.github.truscdo.foldbackup.core.StreamingHash.Hash128();
        try (var input = Files.newInputStream(filePath)) {
            long length = io.github.truscdo.foldbackup.core.StreamingHash.read(input, hash);
            return new FileHash(hash.fileHex(), length);
        }
    }

    /** Find and sort all .mcc files whose chunk coordinates belong to a Region. */
    public static List<Path> findMccFilesForRegion(Path regionDir, int regionX, int regionZ) throws IOException {
        int minX = Math.multiplyExact(regionX, 32);
        int maxX = Math.addExact(minX, 31);
        int minZ = Math.multiplyExact(regionZ, 32);
        int maxZ = Math.addExact(minZ, 31);
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mcc")) {
            for (Path entry : stream) {
                Matcher matcher = MCC_PATTERN.matcher(entry.getFileName().toString());
                if (!matcher.matches()) continue;
                int chunkX = Integer.parseInt(matcher.group(1));
                int chunkZ = Integer.parseInt(matcher.group(2));
                if (chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ)
                    result.add(entry);
            }
        }
        Collections.sort(result);
        return result;
    }
}
