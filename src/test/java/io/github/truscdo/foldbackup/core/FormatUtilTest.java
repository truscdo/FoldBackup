package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FormatUtil} 的单元测试。
 */
class FormatUtilTest {

    @Test
    void formatSize_bytes() {
        assertEquals("1023 B", FormatUtil.formatSize(1023));
    }

    @Test
    void formatSize_kibibytes() {
        assertEquals("1.0K", FormatUtil.formatSize(1024));
    }

    @Test
    void formatSize_mebibytes() {
        assertEquals("1.0M", FormatUtil.formatSize(1024 * 1024));
    }

    @Test
    void formatSize_gibibytes() {
        // FormatUtil 只格式化到 MB；1 GiB = 1024.0M
        assertEquals("1024.0M", FormatUtil.formatSize((long) 1024 * 1024 * 1024));
    }

    @Test
    void formatSize_zero() {
        assertEquals("0 B", FormatUtil.formatSize(0));
    }

    @Test
    void formatSize_rounding() {
        assertEquals("1.5K", FormatUtil.formatSize(1536));
    }

    @Test
    void formatSize_large() {
        long value = 5L * 1024 * 1024 * 1024; // 5 GB → 5120.0M
        assertEquals("5120.0M", FormatUtil.formatSize(value));
    }
}
