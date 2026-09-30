package com.foldbackup.core.store;

import com.foldbackup.core.format.ZstdStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingBlobTest {
    @TempDir Path temp;
    @Test void duplicateSessionsCommitOnceAndCleanup() throws Exception {
        BlobStore blobs=new BlobStore(temp,3);
        try (var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<Future<BlobStore.Commit>>();
            for(int i=0;i<8;i++) tasks.add(pool.submit(() -> {
                try(var session=blobs.newWriteSession()) {
                    var summary=ZstdStream.write(new ByteArrayInputStream(new byte[65536]),session.output(),3);
                    return session.commit(summary.hash(),"zst");
                }
            }));
            int created=0;
            for(var task:tasks) if(task.get().created()) created++;
            assertEquals(1,created);
        }
        try(var files=Files.walk(temp)) { assertTrue(files.noneMatch(p->p.toString().endsWith(".wip"))); }
    }
    @Test void cumulativeSizeLimitIsStickyAndCancelledSessionLeavesNoBlob() throws Exception {
        BlobStore blobs=new BlobStore(temp,3);
        try(var scope=blobs.beginBackupWrites(0,100)) {
            try(var session=blobs.newWriteSession()) {
                session.output().write(new byte[60]);
                assertThrows(IOException.class,()->session.output().write(new byte[60]));
                assertThrows(IOException.class,scope::check);
                assertThrows(IOException.class,()->session.commit("ab1234567890abcd","lrh"));
            }
        }
        try(var files=Files.walk(temp)) { assertEquals(0,files.filter(Files::isRegularFile).count()); }
    }
}
