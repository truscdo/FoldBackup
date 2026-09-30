package com.foldbackup.core.store;

import com.foldbackup.core.cleanup.WriteSpaceBudget;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Content-addressed blob storage.
 * <p>
 * Blobs are stored as {@code blobs/{hash_prefix}/{hash}.{ext}} where
 * {@code hash_prefix} is the first 2 hex characters of the hash.
 * <p>
 * Region LRHs use their XXH3-128 contentHash; regular files use their
 * XXH3-128 sourceHash.
 * <p>
 * All new bytes go through {@link #newWriteSession()}: the session owns a
 * temporary file next to the target, counts every block against the active
 * {@link WriteSpaceBudget}, fsyncs and then atomically moves the result into
 * place. Compression happens in the caller's streaming writer, so a complete
 * blob is never buffered here.
 */
public class BlobStore {

    private final Path rootDir;
    private volatile WriteSpaceBudget writeBudget;

    public void checkBackupWrites() throws IOException {
        WriteSpaceBudget budget = writeBudget;
        if (budget != null)
            budget.check();
    }

    /** 由持有仓库写锁的备份协调器安装，所有 region worker 共用同一预算。 */
    public synchronized WriteScope beginBackupWrites(long indexBytes, long maxBlobBytes) {
        if (writeBudget != null)
            throw new IllegalStateException("Backup write budget already active");
        writeBudget = new WriteSpaceBudget(rootDir, indexBytes, maxBlobBytes);
        return new WriteScope(writeBudget);
    }

    public final class WriteScope implements AutoCloseable {
        private final WriteSpaceBudget budget;

        private WriteScope(WriteSpaceBudget budget) {
            this.budget = budget;
        }

        public void check() throws IOException {
            budget.check();
        }

        @Override
        public void close() {
            writeBudget = null;
        }
    }

    /**
     * @param rootDir          backup store root directory
     * @param compressionLevel zstd level of the surrounding pipeline; the store
     *                         itself does not compress, callers streaming into a
     *                         {@link WriteSession} choose their own level
     */
    public BlobStore(Path rootDir, int compressionLevel) {
        this.rootDir = rootDir;
    }

    /**
     * Check if a blob with the given hash already exists.
     */
    public boolean exists(String hash, String extension) {
        return getPath(hash, extension).toFile().exists();
    }

    /** Check whether a manifest-referenced blob path still exists. */
    public boolean existsPath(String blobPath) {
        return Files.isRegularFile(rootDir.resolve(blobPath));
    }

    /**
     * Store a pre-compressed .lrh blob.
     *
     * @param hash    XXH3-128 content hash of the Region
     * @param lrhData complete .lrh file bytes
     * @return the relative path of the stored blob
     */
    public String storeLrh(String hash, byte[] lrhData) throws IOException {
        Path path = getPath(hash, "lrh");
        if (!path.toFile().exists()) {
            atomicWrite(path, lrhData);
        }
        return getRelativePath(hash, "lrh");
    }

    /**
     * 原子性地将数据写入目标文件：先写入 {@code <destination>.wip}，然后原子性移动。
     */
    private synchronized void atomicWrite(Path destination, byte[] data) throws IOException {
        try {
            writeAtomicChecked(destination, data);
        } catch (IOException e) {
            WriteSpaceBudget budget = writeBudget;
            if (budget != null)
                budget.fail(e);
            throw e;
        }
    }

    private void writeAtomicChecked(Path destination, byte[] data) throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            return;
        }
        WriteSpaceBudget budget = writeBudget;
        if (budget == null) {
            Path index = rootDir.resolve("index.db");
            budget = new WriteSpaceBudget(rootDir, Files.exists(index) ? Files.size(index) : 0, 0);
        }
        budget.beforeWrite(data.length);

        // A unique temporary file prevents concurrent producers of the same
        // content-addressed blob from truncating or moving one another's WIP.
        Path wip = null;
        try {
            wip = Files.createTempFile(destination.getParent(),
                    destination.getFileName().toString() + ".", ".wip");
            Files.write(wip, data);
            try {
                Files.move(wip, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // On Windows, an atomic move to an existing target can be
                // reported as AccessDeniedException instead of
                // FileAlreadyExistsException. Only treat it as a race when
                // the target is actually present; preserve real IO errors.
                if (!Files.exists(destination)) {
                    throw e;
                }
            }
            budget.check();
        } catch (IOException e) {
            budget.fail(e);
            throw e;
        } finally {
            if (wip != null)
                Files.deleteIfExists(wip);
        }
    }

    /**
     * 读取数据块的原始字节。
     *
     * @param blobPath 相对路径（由 storeLrh 或 WriteSession.commit 返回）
     * @return 数据块文件内容
     */
    public byte[] read(String blobPath) throws IOException {
        Path fullPath = rootDir.resolve(blobPath);
        return Files.readAllBytes(fullPath);
    }

    /**
     * 读取并解压缩 .lrh 数据块。
     */
    public byte[] readLrh(String blobPath) throws IOException {
        return read(blobPath); // .lrh is self-contained, read as-is
    }

    /**
     * 读取数据块的前 {@code n} 字节（用于轻量读取 .lrh preheader 等）。
     */
    public byte[] readPrefix(String blobPath, int n) throws IOException {
        Path fullPath = rootDir.resolve(blobPath);
        try (var in = Files.newInputStream(fullPath)) {
            byte[] buf = new byte[n];
            int read = in.read(buf);
            if (read < n) {
                return java.util.Arrays.copyOf(buf, read);
            }
            return buf;
        }
    }

    /**
     * 读取并解压缩 .zst 文件数据块。
     */
    public byte[] readFile(String blobPath) throws IOException {
        try (var input = openFile(blobPath)) {
            return input.readAllBytes();
        }
    }

    public java.io.InputStream open(String blobPath) throws IOException {
        return Files.newInputStream(rootDir.resolve(blobPath));
    }

    public java.io.InputStream openFile(String blobPath) throws IOException {
        var in = open(blobPath);
        try {
            return new com.github.luben.zstd.ZstdInputStream(in);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    public WriteSession newWriteSession() throws IOException {
        return new WriteSession();
    }

    public record Commit(String path, long size, boolean created) {
    }

    /** Storage owns temporary paths, space accounting, durability and commit. */
    public final class WriteSession implements AutoCloseable {
        private final Path temp;
        private final java.nio.channels.FileChannel file;
        private final WriteSpaceBudget budget;
        private boolean closed;
        private final java.nio.channels.SeekableByteChannel channel;

        private WriteSession() throws IOException {
            WriteSpaceBudget active = writeBudget;
            Path index = rootDir.resolve("index.db");
            budget = active != null ? active
                    : new WriteSpaceBudget(rootDir,
                            Files.exists(index) ? Files.size(index) : 0, 0);
            Path pending = rootDir.resolve("blobs");
            Path created = null;
            try {
                Files.createDirectories(pending);
                budget.beginBlob();
                created = Files.createTempFile(pending, "stream-", ".wip");
                file = java.nio.channels.FileChannel.open(created, java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.READ);
                temp = created;
            } catch (IOException e) {
                budget.fail(e);
                if (created != null)
                    Files.deleteIfExists(created);
                throw e;
            }
            channel = new java.nio.channels.SeekableByteChannel() {
                public int write(ByteBuffer src) throws IOException {
                    try {
                        com.foldbackup.core.format.StreamIo.checkCancelled();
                        long end = Math.addExact(file.position(), src.remaining());
                        long size = file.size();
                        budget.beforeBlock(Math.max(end, size), Math.max(0, end - size));
                        return file.write(src);
                    } catch (IOException e) {
                        budget.fail(e);
                        throw e;
                    }
                }

                public int read(ByteBuffer dst) throws IOException {
                    return file.read(dst);
                }

                public long position() throws IOException {
                    return file.position();
                }

                public java.nio.channels.SeekableByteChannel position(long p) throws IOException {
                    file.position(p);
                    return this;
                }

                public long size() throws IOException {
                    return file.size();
                }

                public java.nio.channels.SeekableByteChannel truncate(long n) throws IOException {
                    file.truncate(n);
                    return this;
                }

                public boolean isOpen() {
                    return file.isOpen();
                }

                public void close() {
                } // session owns the file
            };
        }

        public java.nio.channels.SeekableByteChannel channel() {
            return channel;
        }

        public java.io.OutputStream output() {
            return com.foldbackup.core.format.StreamIo.output(channel);
        }

        public Commit commit(String hash, String extension) throws IOException {
            if (closed)
                throw new IOException("Closed write session");
            try {
                com.foldbackup.core.format.StreamIo.checkCancelled();
                com.foldbackup.core.format.IoBudget.call(() -> { file.force(true); return null; });
                file.close();
                Path destination = getPath(hash, extension);
                synchronized (BlobStore.this) {
                    budget.check();
                    Files.createDirectories(destination.getParent());
                    boolean created = !Files.exists(destination);
                    if (created) {
                        try {
                            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException race) {
                            if (!Files.exists(destination))
                                throw race;
                            created = false;
                        }
                    }
                    return new Commit(getRelativePath(hash, extension), Files.size(destination), created);
                }
            } catch (IOException e) {
                budget.fail(e);
                throw e;
            }
        }

        public void close() throws IOException {
            if (closed)
                return;
            closed = true;
            try {
                file.close();
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * 删除单个数据块。
     */
    public void delete(String blobPath) throws IOException {
        Path fullPath = rootDir.resolve(blobPath);
        Files.deleteIfExists(fullPath);
    }

    /**
     * 获取数据块的大小。
     */
    public long size(String blobPath) throws IOException {
        Path fullPath = rootDir.resolve(blobPath);
        return Files.size(fullPath);
    }

    /**
     * 获取给定哈希 + 扩展名的完整路径。
     */
    public Path getPath(String hash, String ext) {
        String prefix = hash.substring(0, 2);
        return rootDir.resolve("blobs").resolve(prefix).resolve(hash + "." + ext);
    }

    /**
     * 获取给定哈希 + 扩展名的相对路径（用于清单存储）。
     */
    public String getRelativePath(String hash, String ext) {
        String prefix = hash.substring(0, 2);
        return "blobs/" + prefix + "/" + hash + "." + ext;
    }

    public Path getRootDir() {
        return rootDir;
    }
}
