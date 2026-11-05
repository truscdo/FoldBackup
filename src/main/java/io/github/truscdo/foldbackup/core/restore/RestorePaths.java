package io.github.truscdo.foldbackup.core.restore;

import java.io.IOException;

/** Rejects restore paths that could escape the target directory. */
final class RestorePaths {
    private RestorePaths() {
    }

    static String validate(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Restore path is empty");
        if (path.indexOf('\\') >= 0) throw new IOException("Restore path contains a backslash: " + path);
        if (path.startsWith("/")) throw new IOException("Restore path is absolute: " + path);
        if (path.indexOf(':') >= 0) throw new IOException("Restore path contains a drive: " + path);
        if (path.endsWith("/")) throw new IOException("Restore path ends with a separator: " + path);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) throw new IOException("Restore path has an empty segment: " + path);
            if (".".equals(segment) || "..".equals(segment))
                throw new IOException("Restore path is not normalized: " + path);
        }
        return path;
    }
}
