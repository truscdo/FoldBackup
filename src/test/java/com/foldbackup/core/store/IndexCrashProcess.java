package com.foldbackup.core.store;

import java.io.IOException;
import java.nio.file.Path;

/** Exit without shutdown hooks at the actual commit boundary in an independent JVM. */
public final class IndexCrashProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        IndexStore disk = new IndexStore(root.resolve("index.db")) {
            @Override void replaceTemporary() throws IOException {
                if (args[1].equals("before")) Runtime.getRuntime().halt(23);
                super.replaceTemporary();
                Runtime.getRuntime().halt(23);
            }
        };
        SnapshotManifest snapshot = new SnapshotManifest("S-20260907_130001", 1, "world");
        snapshot.addExtraFile(new FileEntry("file", "hash", "blobs/live", 1, 2));
        new SnapshotStore(root, disk).saveSnapshot(snapshot);
    }
}
