package io.github.truscdo.foldbackup.core.archive;

import java.io.IOException;
import java.io.InputStream;

/**
 * 基线中的一个普通文件对象：压缩后的 {@code .zst} 原始字节。
 * <p>
 * 只提供压缩字节，是因为两种调用方需要不同的视图：恢复到世界目录要解压，导入要原样复制。
 * 解压由调用方包裹 {@code ZstdInputStream} 完成，复制与解压校验可同时进行。
 */
@FunctionalInterface
interface FileSource {
    /** 打开原始压缩流；调用方负责关闭。 */
    InputStream open() throws IOException;
}
