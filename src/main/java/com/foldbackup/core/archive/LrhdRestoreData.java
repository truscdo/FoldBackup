package com.foldbackup.core.archive;

import com.foldbackup.core.HashUtil;
import com.foldbackup.core.format.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** One forward NBT cursor per LRHD, with replacement payloads staged one Region at a time. */
final class LrhdRestoreData implements AutoCloseable {
    record StagedRegion(LrhdFormat.RegionIndex index, Path payload) implements AutoCloseable {
        @Override public void close() throws IOException {
            if (payload != null) Files.deleteIfExists(payload);
        }
    }

    final List<LrhdFormat.RegionIndex> regions;
    private final LrhdFormat.Index index;
    private final Opener opener;
    private InputStream input;
    private LrhdFormat.DataReader data;
    private int nextRegion;
    private int lastRx, lastRz;
    private boolean mergedRegion;

    @FunctionalInterface private interface Opener { InputStream open() throws IOException; }

    private LrhdRestoreData(LrhdFormat.Index index, Opener opener) {
        this.index = index; this.opener = opener;
        regions = index.regions();
    }

    static LrhdRestoreData read(TarIndex.Entry entry) throws IOException {
        try (var in = entry.open()) {
            return new LrhdRestoreData(LrhdFormat.readIndex(in, entry.size()), entry::open);
        }
    }

    private void openData() throws IOException {
        if (data != null) return;
        input = opener.open();
        try {
            long size;
            try {
                size = Math.addExact(Math.addExact(index.dataOffset(), index.header().compressedDataLength()),
                        LrhdFormat.FOOTER_SIZE);
            } catch (ArithmeticException e) {
                throw new IOException("LRHD file size overflow", e);
            }
            var current = LrhdFormat.readIndex(input, size);
            if (!current.header().equals(index.header())) throw new IOException("LRHD metadata changed during restore");
            data = new LrhdFormat.DataReader(input, index);
        } catch (IOException | RuntimeException | Error e) {
            try { input.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            input = null; throw e;
        }
    }

    /** Reads the next indexed region from this LRHD frame and optionally stages replacement NBT. */
    StagedRegion stageNext(Path payload, boolean retain) throws IOException {
        if (nextRegion >= regions.size()) return null;
        openData();
        var region = regions.get(nextRegion++);
        boolean hasPayload = retain && region.chunks.stream().anyMatch(chunk -> !chunk.deleted);
        try (OutputStream output = hasPayload
                ? Files.newOutputStream(payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                : null) {
            for (var chunk : region.chunks) {
                if (retain && !chunk.deleted) data.transferTo(chunk, output);
                else data.skip(chunk);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (hasPayload) {
                try { Files.deleteIfExists(payload); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        return retain ? new StagedRegion(region, hasPayload ? payload : null) : null;
    }

    RegionStream.Source merge(int rx, int rz, RegionStream.Source base) {
        return output -> {
            if (mergedRegion && LrhdFormat.compareRegions(lastRx, lastRz, rx, rz) >= 0)
                throw new IOException("LRHD restore regions must be consumed in order");
            lastRx = rx; lastRz = rz; mergedRegion = true;
            openData();
            while (nextRegion < regions.size()) {
                var r = regions.get(nextRegion);
                if (LrhdFormat.compareRegions(r.rx, r.rz, rx, rz) >= 0) break;
                for (var c : r.chunks) data.skip(c); // e.g. a whole-region deletion
                nextRegion++;
            }
            var selected = new LrhdFormat.ChunkIndex[1024];
            if (nextRegion < regions.size()) {
                var r = regions.get(nextRegion);
                if (r.rx == rx && r.rz == rz) {
                    for (var c : r.chunks) selected[(c.cz & 31) * 32 + (c.cx & 31)] = c;
                    nextRegion++;
                }
            }
            base.read(new RegionStream.Consumer() {
                LrhdFormat.ChunkIndex replacement;
                public void begin(int i, int timestamp) throws IOException {
                    replacement = selected[i];
                    output.begin(i, replacement == null ? timestamp : (int) replacement.ts);
                    if (replacement != null) data.transfer(replacement, output);
                }
                public void data(byte[] b, int off, int len) throws IOException {
                    if (replacement == null) output.data(b, off, len);
                }
                public void end() throws IOException { output.end(); }
            });
        };
    }

    /** Merges a region whose LRHD replacement payloads were verified and staged by the frame reader. */
    RegionStream.Source mergeStaged(int rx, int rz, RegionStream.Source base, StagedRegion staged) {
        return output -> {
            if (staged == null || staged.index().rx != rx || staged.index().rz != rz)
                throw new IOException("Missing or mismatched staged LRHD region: " + rx + "," + rz);
            var selected = new LrhdFormat.ChunkIndex[1024];
            for (var chunk : staged.index().chunks)
                selected[(chunk.cz & 31) * 32 + (chunk.cx & 31)] = chunk;
            try (InputStream payload = staged.payload() == null ? null
                    : IoBudget.call(() -> Files.newInputStream(staged.payload()))) {
                base.read(new RegionStream.Consumer() {
                    LrhdFormat.ChunkIndex replacement;
                    public void begin(int i, int timestamp) throws IOException {
                        replacement = selected[i];
                        output.begin(i, replacement == null ? timestamp : (int) replacement.ts);
                        if (replacement != null && !replacement.deleted) {
                            if (payload == null) throw new IOException("Missing staged LRHD replacement payload");
                            copyExactly(payload, output, replacement);
                        }
                    }
                    public void data(byte[] bytes, int off, int len) throws IOException {
                        if (replacement == null) output.data(bytes, off, len);
                    }
                    public void end() throws IOException { output.end(); }
                });
                if (payload != null && IoBudget.call(payload::read) != -1)
                    throw new IOException("Unexpected trailing staged LRHD payload");
            }
        };
    }

    private static void copyExactly(InputStream input, RegionStream.Consumer output,
            LrhdFormat.ChunkIndex chunk) throws IOException {
        byte[] buffer = new byte[65536];
        int remaining = chunk.length;
        var hash = new com.foldbackup.core.StreamingHash.Hash128();
        while (remaining > 0) {
            StreamIo.checkCancelled();
            int limit = Math.min(buffer.length, remaining);
            int n = IoBudget.call(() -> input.read(buffer, 0, limit));
            if (n <= 0) throw new EOFException("Truncated staged LRHD replacement");
            hash.update(buffer, 0, n);
            output.data(buffer, 0, n);
            remaining -= n;
        }
        if (!HashUtil.bytesToHex(hash.digest()).equals(chunk.hash))
            throw new IOException("Staged LRHD chunk hash mismatch: " + chunk.cx + "," + chunk.cz);
    }

    /** Consume even hidden/unreferenced changes and verify the end of the frame before publication. */
    void finish() throws IOException {
        openData();
        while (nextRegion < regions.size())
            for (var c : regions.get(nextRegion++).chunks) data.skip(c);
        data.finish();
    }

    public void close() throws IOException {
        var closingData = data; var closingInput = input;
        data = null; input = null;
        try { if (closingData != null) closingData.close(); }
        finally { if (closingInput != null) closingInput.close(); }
    }

    static final class Fileset implements AutoCloseable {
        final Map<String, LrhdRestoreData> entries = new LinkedHashMap<>();
        void add(String directory, TarIndex.Entry entry) throws IOException {
            if (entries.containsKey(directory)) throw new IOException("Duplicate LRHD directory: " + directory);
            entries.put(directory, read(entry));
        }
        public void close() throws IOException {
            IOException failure = null;
            for (var entry : entries.values()) try { entry.close(); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw failure;
        }
    }
}
