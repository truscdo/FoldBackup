package com.foldbackup.core.store;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code index.db} 的二进制索引存储，一次性读写完整索引，
 * 不采用追加写入、检查点或校验和机制。
 * <p>
 * 具备崩溃安全性：先序列化到 {@code .tmp} 文件，再通过原子重命名覆盖正式文件。
 * 临时文件 flush + force(true) 后才原子替换；不支持原子替换时直接报错，不降级。
 * 进程中断后只读取正式索引，遗留 .tmp 不提升为正式文件，下一次保存覆盖它。
 * 外层字段均为大端，EntryMetadata 内部为小端。
 * Java 没有跨平台目录 fsync 保证：这里不保证目录项在断电后持久化，
 * 实际恢复能力还取决于文件系统、设备缓存及其 force/原子移动实现。
 * 调用方必须持有备份目录写入锁，覆盖 load/save 和相关 Blob 操作。
 * <p>
 * 文件格式：
 * <pre>
 * [MAGIC: 4B "MCBI"] [VERSION: 1B = 1]
 * [metadata_count: 4B BE]
 *   [entry_len: 4B BE] [EntryMetadata.toBytes() 数据]  × N
 * [snapshot_count: 4B BE]
 *   [snapshotId_len: 2B][snapshotId UTF-8]
 *   [timeSeconds:    8B BE]  Unix epoch seconds
 *   [worldName_len:  2B][worldName  UTF-8]
 *   [mcVersion_len:  2B][mcVersion  UTF-8]
 *   [worldVersion:   8B BE]
 *   [tags_count:     2B]
 *     [tag_len: 2B][tag UTF-8]  × tags_count
 *   [regionsTotal:          4B BE]
 *   [regionsChanged:        4B BE]
 *   [extraFilesTotal:       4B BE]
 *   [extraFilesChanged:     4B BE]
 *   [totalCompressedBytes:  8B BE]
 *   [totalUncompressedBytes:8B BE]
 *   [newBlobBytes:          8B BE]
 *   [entry_count:    4B]
 *     [metadataHash: 32B ASCII]  × entry_count
 * </pre>
 */
public class IndexStore implements AutoCloseable {

    /** 当前 index.db 二进制格式版本。 */
    private static final int INDEX_VERSION = 1;

    private final Path path;
    private final Path tmpPath;

    /** 初始化索引文件路径及对应的临时文件路径。 */
    public IndexStore(Path path) {
        this.path = path;
        this.tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
    }

    /**
     * 从磁盘加载完整索引并重建内存索引；索引文件不存在时返回空索引。
     */
    public MetadataIndex load() throws IOException {
        if (Files.notExists(path)) {
            return new MetadataIndex();
        }

        long fileSize = Files.size(path);
        try (IndexInput in = new IndexInput(
                new BufferedInputStream(Files.newInputStream(path)), fileSize)) {

            // ── 文件头 ──
            byte[] magic = in.bytes(4, "header.magic");
            if (magic[0] != 'M' || magic[1] != 'C' || magic[2] != 'B' || magic[3] != 'I') {
                throw new IOException("Invalid index.db magic");
            }
            int version = in.u8("header.version");
            if (version != INDEX_VERSION) {
                throw new IOException("Unsupported index.db version: " + version);
            }

            MetadataIndex index = new MetadataIndex();

            // ── 元数据区段 ──
            int metaCount = in.count("metadata_count", 29);
            for (int i = 0; i < metaCount; i++) {
                String field = "metadata[" + i + "]";
                int len = in.i32(field + ".length");
                if (len < 29) throw new IOException(field + ".length: invalid " + len);
                byte[] data = in.bytes(len, field);
                try {
                    EntryMetadata meta = EntryMetadata.fromBytes(data);
                    if (index.getMetadata(meta.getMetadataHash()) != null)
                        throw new IOException("duplicate metadata hash");
                    index.addMetadata(meta);
                } catch (IOException | IllegalArgumentException e) {
                    throw new IOException(field + ": " + e.getMessage(), e);
                }
            }

            // ── 快照区段 ──
            int snapCount = in.count("snapshot_count", 68);
            Set<String> ids = new HashSet<>();
            Set<Long> epochs = new HashSet<>();
            List<LoadedSnapshot> loadedSnapshots = new ArrayList<>();
            for (int i = 0; i < snapCount; i++) {
                String field = "snapshot[" + i + "]";
                String sid = in.utf(field + ".snapshotId");
                if (!SnapshotStore.isValidSnapshotId(sid) || !ids.add(sid))
                    throw new IOException(field + ".snapshotId: invalid or duplicate " + sid);
                long timeSeconds = in.i64(field + ".timeSeconds");
                if (!epochs.add(timeSeconds))
                    throw new IOException(field + ".timeSeconds: duplicate epoch " + timeSeconds);
                String wn = in.utf(field + ".worldName");
                String mv = in.utf(field + ".mcVersion");
                long wv = in.i64(field + ".worldVersion");
                int tagCount = in.u16(field + ".tags_count");
                List<String> tags = new ArrayList<>(tagCount);
                for (int j = 0; j < tagCount; j++) {
                    tags.add(in.utf(field + ".tags[" + j + "]"));
                }

                // ── 摘要 ──
                SnapshotManifest.Summary summary = new SnapshotManifest.Summary();
                summary.setRegionsTotal(in.i32(field + ".summary.RegionsTotal"));
                summary.setRegionsChanged(in.i32(field + ".summary.RegionsChanged"));
                summary.setExtraFilesTotal(in.i32(field + ".summary.ExtraFilesTotal"));
                summary.setExtraFilesChanged(in.i32(field + ".summary.ExtraFilesChanged"));
                summary.setTotalCompressedBytes(in.i64(field + ".summary.TotalCompressedBytes"));
                summary.setTotalUncompressedBytes(in.i64(field + ".summary.TotalUncompressedBytes"));
                summary.setNewBlobBytes(in.i64(field + ".summary.NewBlobBytes"));

                validateSummary(summary, field);
                int entryCount = in.count(field + ".entry_count", 32);
                List<String> entryHashes = new ArrayList<>();
                for (int j = 0; j < entryCount; j++) {
                    String hash = new String(in.bytes(32, field + ".entries[" + j + "]"), StandardCharsets.US_ASCII);
                    validateReference(index, hash, field + ".entries[" + j + "]");
                    entryHashes.add(hash);
                }

                // 重建 SnapshotManifest
                SnapshotManifest manifest = new SnapshotManifest(sid, timeSeconds, wn);
                manifest.setMinecraftVersion(mv.isEmpty() ? null : mv);
                manifest.setWorldVersion(wv);
                manifest.setTags(tags);
                manifest.setSummary(summary);

                // 根据已保存的元数据重建 RegionEntry / FileEntry
                for (String hash : entryHashes) {
                    EntryMetadata meta = index.getMetadata(hash);
                    // References were checked before rebuilding the manifest.
                    if (meta.getType() == EntryMetadata.Type.REGION) {
                        manifest.addRegion(new RegionEntry(
                                meta.getFilename(), meta.getBlobHash(),
                                meta.getBlobPath(), meta.getSizeCompressed(),
                                meta.getSizeUncompressed(), meta.getSourceMetadata()));
                    } else {
                        manifest.addExtraFile(new FileEntry(
                                meta.getFilename(), meta.getBlobHash(),
                                meta.getBlobPath(), meta.getSizeCompressed(),
                                meta.getSizeUncompressed(), meta.getSourceMetadata()));
                    }
                }

                loadedSnapshots.add(new LoadedSnapshot(manifest, entryHashes));
            }

            // 按 Epoch 排序由 MetadataIndex.addSnapshot 保证；逆序恢复保留既有提交顺序。
            for (int i = loadedSnapshots.size() - 1; i >= 0; i--) {
                LoadedSnapshot snapshot = loadedSnapshots.get(i);
                index.addSnapshot(snapshot.manifest(), snapshot.entryMetadataHashes());
            }

            in.end("index.db");
            return index;
        }
    }

    /**
     * 将完整的内存索引写入磁盘，并通过临时文件与原子重命名提交。
     */
    public void save(MetadataIndex index) throws IOException {
        index = index.copy();
        validate(index);
        long required = Math.addExact(serializedSize(index), 1024L * 1024);
        if (Files.getFileStore(path.getParent()).getUsableSpace() < required)
            throw new IOException("Insufficient space for atomic index commit (requires " + required + " bytes)");
        // Validate before touching even the temporary file. Never fall back to non-atomic move.
        try (FileChannel channel = FileChannel.open(tmpPath, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            writeIndex(index, out);
            out.flush();
            forceTemporary(channel);
        }

        replaceTemporary();
    }

    /** 使用同一序列化逻辑计算原子提交临时文件所需字节，不分配完整索引缓冲。 */
    public static long serializedSize(MetadataIndex index) throws IOException {
        class Counter extends OutputStream {
            long count;
            @Override public void write(int value) { count = Math.addExact(count, 1); }
            @Override public void write(byte[] data, int offset, int length) {
                count = Math.addExact(count, length);
            }
        }
        Counter counter = new Counter();
        writeIndex(index, new DataOutputStream(counter));
        return counter.count;
    }

    /** 写会话内回收崩溃遗留的索引临时文件；正式索引绝不由临时文件恢复。 */
    long discardTemporary() throws IOException {
        if (Files.notExists(tmpPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0;
        var attrs = Files.readAttributes(tmpPath, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Not a regular temporary index: " + tmpPath);
        Files.delete(tmpPath);
        return attrs.size();
    }

    private static void writeIndex(MetadataIndex index, DataOutputStream out) throws IOException {

            // ── 文件头 ──
            out.write(new byte[]{'M', 'C', 'B', 'I'});
            out.write(INDEX_VERSION);

            // ── 元数据区段 ──
            List<EntryMetadata> allMeta = index.getAllMetadata();
            out.writeInt(allMeta.size());
            for (EntryMetadata meta : allMeta) {
                byte[] data = meta.toBytes();
                out.writeInt(data.length);
                out.write(data);
            }

            // ── 快照区段 ──
            List<SnapshotManifest> snapshots = index.getSnapshots();
            out.writeInt(snapshots.size());
            for (SnapshotManifest sn : snapshots) {
                writeUTF(out, sn.getSnapshotId());
                // Epoch seconds are stored in the existing time field position.
                out.writeLong(sn.getEpochSeconds());
                writeUTF(out, sn.getWorldName() != null ? sn.getWorldName() : "");
                writeUTF(out, sn.getMinecraftVersion() != null ? sn.getMinecraftVersion() : "");
                out.writeLong(sn.getWorldVersion());

                List<String> tags = sn.getTags();
                out.writeShort(tags != null ? tags.size() : 0);
                if (tags != null) {
                    for (String tag : tags) {
                        writeUTF(out, tag);
                    }
                }

                // ── 摘要 ──
                SnapshotManifest.Summary s = sn.getSummary();
                if (s == null) {
                    s = new SnapshotManifest.Summary();
                }
                out.writeInt(s.getRegionsTotal());
                out.writeInt(s.getRegionsChanged());
                out.writeInt(s.getExtraFilesTotal());
                out.writeInt(s.getExtraFilesChanged());
                out.writeLong(s.getTotalCompressedBytes());
                out.writeLong(s.getTotalUncompressedBytes());
                out.writeLong(s.getNewBlobBytes());

                List<String> hashes = index.getEntryHashes(sn.getEpochSeconds());
                out.writeInt(hashes.size());
                for (String h : hashes) {
                    byte[] hb = h.getBytes(StandardCharsets.US_ASCII);
                    if (hb.length != 32) {
                        throw new IOException("metadataHash must be 32 ASCII chars: " + h);
                    }
                    out.write(hb);
                }
            }
    }

    void forceTemporary(FileChannel channel) throws IOException {
        channel.force(true);
    }

    void replaceTemporary() throws IOException {
        Files.move(tmpPath, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** 关闭索引存储；当前实现没有需要长期持有的资源。 */
    @Override
    public void close() {
        // 无需关闭资源；load()/save() 会在每次调用时单独打开资源
    }

    // ──────────────────────────────────────────────
    //  输入输出辅助方法
    // ──────────────────────────────────────────────

    /** 向索引流中写入一个带两字节长度前缀的 UTF-8 字符串。 */
    private static void writeUTF(DataOutputStream out, String s) throws IOException {
        byte[] bytes;
        try { bytes = EntryMetadata.encode(s, false, "string"); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void validateReference(MetadataIndex index, String hash, String field) throws IOException {
        if (!hash.matches("[0-9a-f]{32}") || index.getMetadata(hash) == null)
            throw new IOException(field + ": invalid or missing metadata reference " + hash);
    }

    private static void validateSummary(SnapshotManifest.Summary s, String field) throws IOException {
        if (s.getRegionsTotal() < 0 || s.getRegionsChanged() < 0 || s.getExtraFilesTotal() < 0
                || s.getExtraFilesChanged() < 0 || s.getTotalCompressedBytes() < 0
                || s.getTotalUncompressedBytes() < 0 || s.getNewBlobBytes() < 0)
            throw new IOException(field + ".summary: negative count or size");
    }

    private static void validate(MetadataIndex index) throws IOException {
        String field = "metadata";
        try {
            for (EntryMetadata meta : index.getAllMetadata()) meta.toBytes();
            Set<String> ids = new HashSet<>();
            Set<Long> epochs = new HashSet<>();
            for (SnapshotManifest sn : index.getSnapshots()) {
                field = "snapshot[" + sn.getSnapshotId() + "]";
                if (!SnapshotStore.isValidSnapshotId(sn.getSnapshotId()) || !ids.add(sn.getSnapshotId()))
                    throw new IOException(field + ".snapshotId: invalid or duplicate");
                if (!epochs.add(sn.getEpochSeconds()))
                    throw new IOException(field + ".timeSeconds: duplicate epoch " + sn.getEpochSeconds());
                EntryMetadata.encode(sn.getSnapshotId(), false, "snapshotId");
                EntryMetadata.encode(sn.getWorldName() == null ? "" : sn.getWorldName(), false, "worldName");
                EntryMetadata.encode(sn.getMinecraftVersion() == null ? "" : sn.getMinecraftVersion(), false, "mcVersion");
                if (sn.getTags() != null) {
                    if (sn.getTags().size() > 65535) throw new IOException(field + ".tags_count: exceeds 65535");
                    for (int i = 0; i < sn.getTags().size(); i++)
                        EntryMetadata.encode(sn.getTags().get(i), false, "tags[" + i + "]");
                }
                if (sn.getSummary() != null) validateSummary(sn.getSummary(), field);
                for (String hash : index.getEntryHashes(sn.getEpochSeconds()))
                    validateReference(index, hash, field + ".entries");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(field + ": " + e.getMessage(), e);
        }
    }

    /** 暂存从磁盘读取的快照及其条目引用，待全部读取后按正确顺序恢复。 */
    private record LoadedSnapshot(
            SnapshotManifest manifest,
            List<String> entryMetadataHashes) {
    }
}
