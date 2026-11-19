package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FormatUtil} 的单元测试。
 */
class FormatUtilTest {

    @Test
    void formatSize_bytes() {
        assertEquals("1023B", FormatUtil.formatSize(1023));
    }

    @Test
    void formatSize_kibibytes() {
        assertEquals("1.0K", FormatUtil.formatSize(1024));
    }

    @Test
    void formatSize_mebibytes() {
        assertEquals("1.0M", FormatUtil.formatSize(1024 * 1024));
        assertEquals("1024.0M", FormatUtil.formatSize(1024L * 1024 * 1024 - 1));
    }

    @Test
    void formatSize_gibibytes() {
        assertEquals("1.0G", FormatUtil.formatSize(1024L * 1024 * 1024));
        assertEquals("2.0G", FormatUtil.formatSize(2L * 1024 * 1024 * 1024));
    }

    @Test
    void formatSize_zero() {
        assertEquals("0B", FormatUtil.formatSize(0));
    }

    @Test
    void formatSize_rounding() {
        assertEquals("1.5K", FormatUtil.formatSize(1536));
    }

    @Test
    void formatSize_large() {
        long value = 5L * 1024 * 1024 * 1024;
        assertEquals("5.0G", FormatUtil.formatSize(value));
    }

    @Test
    void formatEpoch_formatsUtcAndLocalZonesConsistently() {
        assertEquals("1970-01-01 00:00:00", FormatUtil.formatEpoch(0L, ZoneOffset.UTC));
        assertEquals("1970-01-01 01:01:01", FormatUtil.formatEpoch(3661L, ZoneOffset.UTC));
        assertEquals("1970-01-01 09:01:01", FormatUtil.formatEpoch(3661L, ZoneOffset.ofHours(8)));

        long sampleEpoch = 1_790_673_600L;
        String expectedLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(Instant.ofEpochSecond(sampleEpoch).atZone(ZoneId.systemDefault()));
        assertEquals(expectedLocal, FormatUtil.formatEpoch(sampleEpoch));
    }
}
