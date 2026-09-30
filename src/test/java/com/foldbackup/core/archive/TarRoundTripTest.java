package com.foldbackup.core.archive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TarRoundTripTest {

    @Test
    void roundTrip_simpleEntries() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarWriter w = new TarWriter(bos)) {
            w.putEntry("snapshot.bin", new byte[] { 9, 8, 7 });
            w.putEntry("objects/regions/0123456789abcdef0123456789abcdef.lrh", new byte[] { 1, 2, 3, 4, 5 });
            w.putEntry("objects/files/fedcba9876543210fedcba9876543210.zst", new byte[0]); // empty entry
        }

        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        try (TarReader r = new TarReader(new ByteArrayInputStream(bos.toByteArray()))) {
            TarReader.Entry e;
            while ((e = r.nextEntry()) != null) {
                assertTrue(e.isFile);
                names.add(e.name);
                datas.add(r.readEntryData());
            }
        }

        assertEquals(List.of("snapshot.bin", "objects/regions/0123456789abcdef0123456789abcdef.lrh",
                "objects/files/fedcba9876543210fedcba9876543210.zst"), names);
        assertArrayEquals(new byte[] { 9, 8, 7 }, datas.get(0));
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, datas.get(1));
        assertEquals(0, datas.get(2).length);
    }

    @Test
    void roundTrip_longNameUsesPrefix() throws Exception {
        // Long directory prefix (>100 chars) with a short base name → ustar prefix
        // split
        String longName = "objects/regions/" + "subdir/".repeat(15)
                + "0123456789abcdef0123456789abcdef.lrh";
        assertTrue(longName.length() > 100);
        assertTrue(longName.length() <= 255);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarWriter w = new TarWriter(bos)) {
            w.putEntry(longName, new byte[] { 42 });
        }

        try (TarReader r = new TarReader(new ByteArrayInputStream(bos.toByteArray()))) {
            TarReader.Entry e = r.nextEntry();
            assertNotNull(e);
            assertEquals(longName, e.name);
            assertArrayEquals(new byte[] { 42 }, r.readEntryData());
            assertNull(r.nextEntry());
        }
    }

    @Test
    void roundTrip_largeEntry() throws Exception {
        byte[] big = new byte[100_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarWriter w = new TarWriter(bos)) {
            w.putEntry("objects/regions/0123456789abcdef0123456789abcdef.lrh", big);
        }
        try (TarReader r = new TarReader(new ByteArrayInputStream(bos.toByteArray()))) {
            TarReader.Entry e = r.nextEntry();
            assertNotNull(e);
            assertEquals(100_000, e.size);
            assertArrayEquals(big, r.readEntryData());
        }
    }

    @Test
    void emptyArchive() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarWriter w = new TarWriter(bos)) {
            // no entries
        }
        try (TarReader r = new TarReader(new ByteArrayInputStream(bos.toByteArray()))) {
            assertNull(r.nextEntry());
        }
    }
}
