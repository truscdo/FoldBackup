package io.github.truscdo.foldbackup.core.format;

import io.github.truscdo.foldbackup.core.StreamingHash;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.List;

/** LRHD v1 assembly, independent of bounded parallel frame production. */
public final class LrhdStreamWriter {
    @FunctionalInterface public interface RegionVisitor {
        void accept(LrhdFormat.RegionIndex region) throws IOException;
    }
    /** Repeatable traversal that need retain only one Region's metadata at a time. */
    @FunctionalInterface public interface PlanSource {
        void visit(RegionVisitor visitor) throws IOException;
    }

    private LrhdStreamWriter() {}

    /** Archive-thread-only assembly; frame bytes are appended between construction and finish. */
    public static final class ArchiveWriter {
        private final SeekableByteChannel output;
        private final int level;
        private final PlanSource plans;
        private final long start, dataOffset;
        private final LrhdFrameProducer.Summary expected;
        private boolean finished;

        public ArchiveWriter(SeekableByteChannel output, int level, PlanSource plans) throws IOException {
            if (level < Short.MIN_VALUE || level > Short.MAX_VALUE) throw new IOException("Invalid compression level");
            this.output = output; this.level = level; this.plans = plans;
            start = output.position();
            StreamIo.write(output, new byte[LrhdFormat.HEADER_SIZE]);
            expected = writeIndex(null);
            dataOffset = output.position() - start;
        }

        private LrhdFrameProducer.Summary writeIndex(StreamingHash.Hash128 hash) throws IOException {
            int[] counts = new int[2], previous = new int[2];
            long[] length = new long[1];
            plans.visit(region -> {
                StreamIo.checkCancelled();
                if (counts[0] > 0)
                    LrhdFormat.validateRegionAfter(previous[0], previous[1], region.rx, region.rz);
                LrhdFormat.writeIndex(output, List.of(region), hash);
                try {
                    counts[0] = Math.addExact(counts[0], 1);
                    counts[1] = Math.addExact(counts[1], region.chunks.size());
                    for (var chunk : region.chunks) length[0] = Math.addExact(length[0], chunk.length);
                } catch (ArithmeticException e) { throw new IOException("LRHD plan size overflow", e); }
                previous[0] = region.rx; previous[1] = region.rz;
            });
            return new LrhdFrameProducer.Summary(counts[0], counts[1], length[0]);
        }

        public void finish(LrhdFrameProducer.Summary actual) throws IOException {
            if (finished) return;
            if (!expected.equals(actual)) throw new IOException("LRHD frame differs from its metadata plan");
            long compressed = output.position() - start - dataOffset;
            if (compressed < 0 || (actual.dataLength() == 0) != (compressed == 0))
                throw new IOException("Invalid LRHD compressed data length");
            StreamIo.write(output, ByteBuffer.allocate(LrhdFormat.FOOTER_SIZE).putLong(LrhdFormat.SIGNATURE).array());
            long end = output.position();
            ByteBuffer header = ByteBuffer.allocate(LrhdFormat.HEADER_SIZE);
            header.putLong(LrhdFormat.SIGNATURE).put(LrhdFormat.VERSION).put((byte) 0)
                    .putShort((short) level).putInt(actual.regions()).putInt(actual.chunks())
                    .putLong(actual.dataLength()).putLong(compressed).put(new byte[LrhdFormat.METADATA_HASH_SIZE]);
            var hash = new StreamingHash.Hash128();
            hash.update(header.array(), 0, header.capacity());
            output.position(start);
            StreamIo.write(output, header.array());
            if (!expected.equals(writeIndex(hash)) || output.position() != start + dataOffset)
                throw new IOException("LRHD metadata plan changed");
            output.position(start + LrhdFormat.METADATA_HASH_OFFSET);
            StreamIo.write(output, hash.digest());
            output.position(end);
            finished = true;
        }
    }

}
