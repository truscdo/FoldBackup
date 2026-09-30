package com.foldbackup.core.store;

import java.nio.file.Path;

/** 独立 JVM 回归测试入口；通过标准输入控制加载后写入的时机。 */
public final class SnapshotWriterProcess {
    public static void main(String[] args) throws Exception {
        SnapshotStore store = new SnapshotStore(Path.of(args[0]));
        System.out.println("READY");
        System.out.flush();
        System.in.read();
        try (SnapshotStore.WriteSession ignored = store.beginWrite()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
            store.saveSnapshot(new SnapshotManifest(args[1], 1, "world"));
        }
    }
}
