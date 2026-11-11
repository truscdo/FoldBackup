package io.github.truscdo.foldbackup.mod.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SavePauseLeaseTest {

    @Test
    void expiresOnlyAfterTheTimeoutWithoutActivity() {
        SavePauseLease lease = new SavePauseLease();
        assertFalse(lease.expired(1_000L, 500L), "An idle lease never expires");

        lease.begin(1_000L);
        assertTrue(lease.isActive());
        assertFalse(lease.expired(1_499L, 500L));
        assertTrue(lease.expired(1_500L, 500L));

        lease.activity(2_000L);
        assertFalse(lease.expired(2_499L, 500L));
        assertTrue(lease.expired(2_500L, 500L));
    }

    @Test
    void nonPositiveTimeoutDisablesTheCheck() {
        SavePauseLease lease = new SavePauseLease();
        lease.begin(0L);
        assertFalse(lease.expired(Long.MAX_VALUE / 2, 0L));
        assertFalse(lease.expired(Long.MAX_VALUE / 2, -1L));
    }

    @Test
    void endedLeaseIgnoresFurtherActivityAndNeverExpiresAgain() {
        SavePauseLease lease = new SavePauseLease();
        lease.begin(1_000L);
        lease.end();
        lease.activity(9_000L);
        assertFalse(lease.isActive());
        assertFalse(lease.expired(10_000L, 1L));
    }
}
