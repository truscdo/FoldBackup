package io.github.truscdo.foldbackup.core;

import net.openhft.hashing.LongTupleHashFunction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 在代码库中共享的哈希工具方法。
 * <p>
 * 集中化 XXH3-128 哈希和十六进制编码，使每个使用者使用相同的实现——
 * 消除重复和细微差异的风险。
 */
public final class HashUtil {

    private HashUtil() {
    }

    /**
     * 计算字节数组的 XXH3-128 哈希。
     *
     * @param data 输入字节
     * @return 16 字节 XXH3-128 哈希（小端序 long[2] 序列化）
     */
    public static byte[] xxHash128(byte[] data) {
        long[] hash = LongTupleHashFunction.xx128().hashBytes(data);
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(hash[0]);
        buf.putLong(hash[1]);
        return buf.array();
    }

    /**
     * 计算字节数组的 XXH3-128 哈希，并以小写十六进制字符串形式返回结果。
     *
     * @param data 输入字节
     * @return 32 字符小写十六进制字符串
     */
    public static String xxHash128Hex(byte[] data) {
        return bytesToHex(xxHash128(data));
    }

    /**
     * 将字节转换为小写十六进制字符串。
     *
     * @param bytes 输入字节
     * @return 十六进制字符串，长度 = 2 × {@code bytes.length}
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 将小写十六进制字符串解码为字节数组。
     *
     * @param hex 十六进制字符串（长度必须为偶数）
     * @return 解码后的字节数组
     * @throws IllegalArgumentException 输入不是合法的小写十六进制字符串
     */
    public static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length: " + hex);
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex string: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
