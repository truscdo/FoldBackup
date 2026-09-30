package com.foldbackup.core.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 写入期间只监测，不运行 GC。失败为粘性状态，扫描器不能吞掉写入失败后提交快照。 */
public final class WriteSpaceBudget {
    public static final long SAFETY_BYTES = 1024L * 1024;
    @FunctionalInterface public interface SpaceProbe { long usableBytes() throws IOException; }

    private final SpaceProbe space;
    private final long maxBlobBytes;
    private long reserveBytes;
    private IOException failure;

    public WriteSpaceBudget(Path root, long indexBytes, long maxBlobBytes) {
        this(() -> Files.getFileStore(root).getUsableSpace(), indexBytes, maxBlobBytes);
    }

    public WriteSpaceBudget(SpaceProbe space, long indexBytes, long maxBlobBytes) {
        if (indexBytes < 0 || maxBlobBytes < 0) throw new IllegalArgumentException("Negative write budget");
        this.space = space;
        this.reserveBytes = Math.addExact(SAFETY_BYTES, indexBytes);
        this.maxBlobBytes = maxBlobBytes;
    }

    public synchronized void beforeWrite(long bytes) throws IOException {
        check();
        try {
            if (maxBlobBytes > 0 && bytes > maxBlobBytes)
                throw new IOException("Single blob cannot fit repository capacity budget: " + bytes);
            // New entries grow the pending index; exact serialized size is checked again at commit.
            reserveBytes = Math.addExact(reserveBytes, 4096);
            long required = Math.addExact(bytes, reserveBytes);
            if (space.usableBytes() < required)
                throw new IOException("Insufficient write space; requires " + required + " bytes including index reserve");
        } catch (IOException | ArithmeticException e) {
            fail(e instanceof IOException io ? io : new IOException("Write budget overflow", e));
            check();
        }
    }

    public synchronized void check() throws IOException {
        if (failure != null) throw failure;
        try {
            if (space.usableBytes() < reserveBytes)
                throw new IOException("Insufficient remaining space for index commit: " + reserveBytes);
        } catch (IOException e) {
            fail(e);
            throw e;
        }
    }

    /** One reservation per output, independent of the number of buffer writes. */
    public synchronized void beginBlob() throws IOException { beforeWrite(0); }

    public synchronized void beforeBlock(long totalBytes, long growth) throws IOException {
        check();
        try {
            if (totalBytes < 0 || growth < 0 || (maxBlobBytes > 0 && totalBytes > maxBlobBytes))
                throw new IOException("Single blob cannot fit repository capacity budget: " + totalBytes);
            if (space.usableBytes() < Math.addExact(reserveBytes, growth))
                throw new IOException("Insufficient write space for next block");
        } catch (IOException | ArithmeticException e) {
            fail(e instanceof IOException io ? io : new IOException("Write budget overflow", e));
            check();
        }
    }

    public synchronized void fail(IOException error) { if (failure == null) failure = error; }
}
