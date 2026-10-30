package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.store.BlobStore;
import io.github.truscdo.foldbackup.core.store.FileEntry;
import io.github.truscdo.foldbackup.core.store.RegionEntry;
import io.github.truscdo.foldbackup.core.store.SnapshotManifest;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以在线快照为基线：清单来自 {@link SnapshotManifest}，内容来自 {@link BlobStore}。
 * <p>
 * 只做对象存在性判断与按需读取，不做全量校验：基线内容在对应 full 归档导入时已逐字节落盘并
 * 校验过，这里再读一遍只是同一份字节的第二次读取。
 * <p>
 * blob 缺失时按“对象不存在”处理而不是抛错——由调用方给出带路径与来源的明确报错。
 */
final class SnapshotBaseline implements Baseline {

    private final SnapshotManifest snapshot;
    private final BlobStore blobs;
    private final Map<String, RegionInfo> regions = new LinkedHashMap<>();
    private final Map<String, FileInfo> files = new LinkedHashMap<>();
    private final Map<String, String> regionBlobs = new HashMap<>();
    private final Map<String, String> fileBlobs = new HashMap<>();
    private final Map<String, Integer> levels = new HashMap<>();

    SnapshotBaseline(SnapshotManifest snapshot, BlobStore blobs) {
        if (snapshot == null) throw new IllegalArgumentException("Missing baseline snapshot manifest");
        this.snapshot = snapshot;
        this.blobs = blobs;
        if (snapshot.getRegions() != null) for (RegionEntry region : snapshot.getRegions()) {
            regions.put(region.getFilename(), new RegionInfo(region.getFilename(), region.getContentHash(),
                    region.getSizeUncompressed(), region.getSourceMetadata()));
            if (region.getBlobPath() != null) regionBlobs.put(region.getContentHash(), region.getBlobPath());
        }
        if (snapshot.getExtraFiles() != null) for (FileEntry file : snapshot.getExtraFiles()) {
            files.put(file.getFilename(), new FileInfo(file.getFilename(), file.getSourceHash(),
                    file.getSizeUncompressed(), file.getSourceMetadata()));
            if (file.getBlobPath() != null) fileBlobs.put(file.getSourceHash(), file.getBlobPath());
        }
    }

    @Override
    public long epoch() {
        return snapshot.getEpochSeconds();
    }

    @Override
    public Map<String, RegionInfo> regions() {
        return Collections.unmodifiableMap(regions);
    }

    @Override
    public Map<String, FileInfo> files() {
        return Collections.unmodifiableMap(files);
    }

    @Override
    public RegionObject regionObject(String contentHash) throws IOException {
        String path = presentRegion(contentHash);
        if (path == null) return null;
        return RegionObject.of(() -> blobs.open(path), blobs.size(path));
    }

    @Override
    public int regionLevel(String contentHash) throws IOException {
        Integer cached = levels.get(contentHash);
        if (cached != null) return cached;
        String path = presentRegion(contentHash);
        if (path == null) throw new IOException("Baseline Region blob is missing: " + contentHash);
        int level = LrhFormat.level(blobs.readPrefix(path, LrhFormat.LEVEL_PREFIX_SIZE));
        levels.put(contentHash, level);
        return level;
    }

    @Override
    public FileSource fileSource(String sourceHash) {
        String path = fileBlobs.get(sourceHash);
        if (path == null || !blobs.existsPath(path)) return null;
        return () -> blobs.open(path);
    }

    @Override
    public long fileObjectSize(String sourceHash) throws IOException {
        String path = fileBlobs.get(sourceHash);
        if (path == null || !blobs.existsPath(path)) return -1;
        return blobs.size(path);
    }

    /** 清单中登记且文件仍在的 Region blob 相对路径；否则 null。 */
    private String presentRegion(String contentHash) {
        String path = regionBlobs.get(contentHash);
        return path == null || !blobs.existsPath(path) ? null : path;
    }
}
