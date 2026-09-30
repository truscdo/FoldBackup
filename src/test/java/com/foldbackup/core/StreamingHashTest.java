package com.foldbackup.core;

import net.openhft.hashing.LongHashFunction;
import net.openhft.hashing.LongTupleHashFunction;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class StreamingHashTest {
    @Test void matchesOriginalForBoundariesSeedsAndPartitions() {
        for (int size : new int[]{0,1,3,4,8,9,16,17,128,129,240,241,1023,1024,1025,65536,100001}) {
            byte[] bytes = new byte[size]; new Random(size).nextBytes(bytes);
            for (int step : new int[]{1,7,64,1024,65536}) {
                var hash = new StreamingHash.Hash128();
                for (int i = 0; i < size; i += step) hash.update(bytes, i, Math.min(step, size-i));
                assertArrayEquals(HashUtil.xxHash128(bytes), hash.digest(), "size=" + size + " step=" + step);
                long[] original = LongTupleHashFunction.xx128().hashBytes(bytes);
                assertEquals(String.format("%016x%016x", original[0], original[1]), hash.fileHex());
                hash.reset(); hash.update(bytes, 0, size); assertArrayEquals(HashUtil.xxHash128(bytes), hash.digest());
                for (long seed : new long[]{0,1,-1,0x123456789abcdefL}) {
                    var h64 = new StreamingHash.Hash64(seed);
                    for (int i = 0; i < size; i += step) h64.update(bytes, i, Math.min(step, size-i));
                    assertEquals(LongHashFunction.xx3(seed).hashBytes(bytes), h64.digest());
                }
            }
        }
    }
}
