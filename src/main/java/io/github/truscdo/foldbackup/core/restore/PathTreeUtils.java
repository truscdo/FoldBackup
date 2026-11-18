package io.github.truscdo.foldbackup.core.restore;

/** Path depth and parent helpers for slash-delimited restore relative paths. */
final class PathTreeUtils {
    private PathTreeUtils() {}

    static int depth(String path) {
        if (path.isEmpty()) return 0;
        int depth = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') depth++;
        }
        return depth;
    }

    static String parentOf(String path) {
        if (path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
