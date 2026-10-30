package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.LrhFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 以 full 归档为基线：{@link TarIndex} 提供对象条目，{@code snapshot.bin} 提供清单。
 * <p>
 * 构造即校验归档形状：只允许 {@code snapshot.bin} 与两类内容对象，且对象集合与清单
 * 的内容身份集合完全一致。这样“基线是否自洽”只有一处判断，恢复、校验与导入不再各写一遍。
 * <p>
 * 不持有打开的文件：每个对象按读取时的偏移独立打开，因此实例可跨线程共享。
 */
final class ArchiveBaseline implements Baseline {

    private static final String REGION_PREFIX = "objects/regions/";
    private static final String FILE_PREFIX = "objects/files/";

    private final ArchiveSnapshot meta;
    private final Map<String, TarIndex.Entry> regionEntries = new HashMap<>();
    private final Map<String, TarIndex.Entry> fileEntries = new HashMap<>();
    private final Map<String, RegionInfo> regions = new LinkedHashMap<>();
    private final Map<String, FileInfo> files = new LinkedHashMap<>();
    private final Map<String, Integer> levels = new HashMap<>();

    ArchiveBaseline(Path tar) throws IOException {
        this(TarIndex.read(tar));
    }

    /** 复用调用方已建立的索引，避免同一份归档被重复扫描。 */
    ArchiveBaseline(TarIndex index) throws IOException {
        this.meta = index.snapshot("full");
        for (var entry : index.entries.values()) {
            String name = entry.name();
            if (name.equals("snapshot.bin")) continue;
            if (name.startsWith(REGION_PREFIX))
                regionEntries.put(ArchiveObjects.hashOf(name, REGION_PREFIX, ".lrh"), entry);
            else if (name.startsWith(FILE_PREFIX))
                fileEntries.put(ArchiveObjects.hashOf(name, FILE_PREFIX, ".zst"), entry);
            else throw new IOException("Unexpected full archive entry: " + name);
        }
        Set<String> regionHashes = new HashSet<>();
        for (var info : meta.regions) {
            TarIndex.validatePath(info.filename);
            if (regions.put(info.filename, new RegionInfo(info.filename, info.contentHash,
                    info.sizeUncompressed, info.sourceMetadata)) != null)
                throw new IOException("Duplicate region metadata: " + info.filename);
            regionHashes.add(info.contentHash);
        }
        Set<String> fileHashes = new HashSet<>();
        for (var info : meta.files) {
            TarIndex.validatePath(info.filename);
            if (files.put(info.filename, new FileInfo(info.filename, info.sourceHash,
                    info.sizeUncompressed, info.sourceMetadata)) != null)
                throw new IOException("Duplicate file metadata: " + info.filename);
            fileHashes.add(info.sourceHash);
        }
        if (!regionEntries.keySet().equals(regionHashes))
            throw new IOException("Full archive Region objects do not match snapshot manifest");
        if (!fileEntries.keySet().equals(fileHashes))
            throw new IOException("Full archive file objects do not match snapshot manifest");
    }

    /** 该归档的清单；仅归档基线具备。 */
    ArchiveSnapshot meta() {
        return meta;
    }

    @Override
    public long epoch() {
        return meta.epoch;
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
    public RegionObject regionObject(String contentHash) {
        TarIndex.Entry entry = regionEntries.get(contentHash);
        return entry == null ? null : RegionObject.of(entry::open, entry.size());
    }

    @Override
    public int regionLevel(String contentHash) throws IOException {
        Integer cached = levels.get(contentHash);
        if (cached != null) return cached;
        TarIndex.Entry entry = regionEntries.get(contentHash);
        if (entry == null) throw new IOException("Baseline Region object is missing: " + contentHash);
        int level = LrhFormat.level(entry.prefix(LrhFormat.LEVEL_PREFIX_SIZE));
        levels.put(contentHash, level);
        return level;
    }

    @Override
    public FileSource fileSource(String sourceHash) {
        TarIndex.Entry entry = fileEntries.get(sourceHash);
        return entry == null ? null : entry::open;
    }

    @Override
    public long fileObjectSize(String sourceHash) {
        TarIndex.Entry entry = fileEntries.get(sourceHash);
        return entry == null ? -1 : entry.size();
    }
}
