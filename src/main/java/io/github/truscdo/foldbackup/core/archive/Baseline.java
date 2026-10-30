package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.LrhFormat;
import io.github.truscdo.foldbackup.core.format.RegionStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 基线内容来源：导入、恢复、校验共用的唯一一份“取基线”实现。
 * <p>
 * 来源按路径固定，不存在候选顺序：差异导入读在线快照（{@link SnapshotBaseline}），
 * 恢复与归档校验读 full 归档（{@link ArchiveBaseline}）。保留两个实现，是为了让同一份
 * 合并代码同时服务这三条路径，而不是让调用方在两者之间挑选。
 */
interface Baseline extends AutoCloseable {

    /** 基线的 Epoch 身份。 */
    long epoch();

    /** 目标状态的 Region 条目，按路径索引。 */
    Map<String, RegionInfo> regions();

    /** 目标状态的普通文件条目，按路径索引。 */
    Map<String, FileInfo> files();

    /** Region 内容对象；对象缺失返回 {@code null}。 */
    RegionObject regionObject(String contentHash) throws IOException;

    /** Region 对象帧的写入级别，用于推导解码内存；对象缺失时抛错。 */
    int regionLevel(String contentHash) throws IOException;

    /** 文件对象的原始压缩流；对象缺失返回 {@code null}。 */
    FileSource fileSource(String sourceHash) throws IOException;

    /** 文件对象的压缩字节数；对象缺失返回 {@code -1}。 */
    long fileObjectSize(String sourceHash) throws IOException;

    /**
     * 一个 Region 内容对象的三种视图：原样复制用 {@link #raw()}，重建或比对内容用
     * {@link #decoded()}，计量用 {@link #compressedSize()}。
     * <p>
     * 解码只在这里实现一次，两个基线实现与全部调用点共用。
     */
    record RegionObject(long compressedSize, RegionStream.Source decoded, FileSource raw) {

        static RegionObject of(FileSource raw, long compressedSize) {
            return new RegionObject(compressedSize, consumer -> {
                try (InputStream input = raw.open()) {
                    LrhFormat.read(input, compressedSize, consumer);
                }
            }, raw);
        }
    }

    @Override
    default void close() throws IOException {
    }
}
