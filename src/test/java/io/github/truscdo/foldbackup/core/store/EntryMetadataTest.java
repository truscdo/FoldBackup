package io.github.truscdo.foldbackup.core.store;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EntryMetadataTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @Test void regionMetadataRoundTripsContentIdentityAndSourceAttributes() throws Exception {
        SourceMetadata source = new SourceMetadata(List.of(
                new SourceMetadata.SourceFile("region/r.0.0.mca", 1024, 1234, "mca-key"),
                new SourceMetadata.SourceFile("region/c.0.0.mcc", 2048, 2345, "mcc-key")));
        EntryMetadata original = new EntryMetadata(EntryMetadata.Type.REGION, "region/r.0.0.mca", HASH,
                "blobs/01/0123456789abcdef0123456789abcdef.lrh", 512, 3072, source);

        EntryMetadata restored = EntryMetadata.fromBytes(original.toBytes());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getFilename(), restored.getFilename());
        assertEquals(original.getBlobHash(), restored.getBlobHash());
        assertEquals(original.getBlobPath(), restored.getBlobPath());
        assertEquals(original.getSizeCompressed(), restored.getSizeCompressed());
        assertEquals(original.getSizeUncompressed(), restored.getSizeUncompressed());
        assertEquals(source, restored.getSourceMetadata());
        assertEquals(original.getMetadataHash(), restored.getMetadataHash());
    }

    @Test void fileMetadataRoundTrips() throws Exception {
        EntryMetadata original = new EntryMetadata(EntryMetadata.Type.FILE, "level.dat", HASH,
                "blobs/01/0123456789abcdef0123456789abcdef.zst", 512, 1024,
                new SourceMetadata(List.of(new SourceMetadata.SourceFile("level.dat", 1024, 5678, "file-key"))));
        EntryMetadata restored = EntryMetadata.fromBytes(original.toBytes());
        assertEquals(EntryMetadata.Type.FILE, restored.getType());
        assertEquals(HASH, restored.getBlobHash());
        assertEquals(original.getSourceMetadata(), restored.getSourceMetadata());
    }

    @Test void fromBytesRejectsInvalidData() {
        assertThrows(Exception.class, () -> EntryMetadata.fromBytes(new byte[] {0, 1, 2, 3, 4, 5}));
    }

    @Test void metadataHashIncludesIdentityAndSourceMetadata() {
        var source = SourceMetadata.empty();
        EntryMetadata a = new EntryMetadata(EntryMetadata.Type.REGION, "r.0.0.mca", HASH, "blobs/a.lrh", 100, 200, source);
        EntryMetadata same = new EntryMetadata(EntryMetadata.Type.REGION, "r.0.0.mca", HASH, "blobs/a.lrh", 100, 200, source);
        EntryMetadata other = new EntryMetadata(EntryMetadata.Type.REGION, "r.0.0.mca",
                "fedcba9876543210fedcba9876543210", "blobs/b.lrh", 100, 200, source);
        assertEquals(a.getMetadataHash(), same.getMetadataHash());
        assertNotEquals(a.getMetadataHash(), other.getMetadataHash());
    }

    @Test void nullFilenameIsRejected() {
        assertThrows(NullPointerException.class, () -> new EntryMetadata(
                EntryMetadata.Type.FILE, null, HASH, "blob", 0, 0, SourceMetadata.empty()));
    }
}
