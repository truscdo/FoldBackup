package com.foldbackup.core.cleanup;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class WriteSpaceBudgetTest {
    @Test
    void reservesTemporaryWriteAndIndexSpaceAndFailureIsSticky() throws Exception {
        AtomicLong free = new AtomicLong(WriteSpaceBudget.SAFETY_BYTES + 10000);
        var budget = new WriteSpaceBudget(free::get, 1000, 0);
        budget.beforeWrite(100);
        free.set(WriteSpaceBudget.SAFETY_BYTES);
        IOException error = assertThrows(IOException.class, () -> budget.beforeWrite(100));
        free.set(Long.MAX_VALUE);
        assertSame(error, assertThrows(IOException.class, budget::check));
    }

    @Test
    void singleBlobLargerThanBudgetFailsBeforeWriting() {
        var budget = new WriteSpaceBudget(() -> Long.MAX_VALUE, 0, 100);
        assertThrows(IOException.class, () -> budget.beforeWrite(101));
    }

    @Test
    void writeFailureCannotBeSwallowedByScan() {
        var budget = new WriteSpaceBudget(() -> Long.MAX_VALUE, 0, 0);
        IOException error = new IOException("disk write failed");
        budget.fail(error);
        assertSame(error, assertThrows(IOException.class, budget::check));
    }

    @Test
    void failedFreeSpaceQueryAndOverflowAreErrors() {
        var failed = new WriteSpaceBudget(() -> {
            throw new IOException("probe failed");
        }, 0, 0);
        assertThrows(IOException.class, failed::check);
        var overflow = new WriteSpaceBudget(() -> Long.MAX_VALUE, 0, 0);
        assertThrows(IOException.class, () -> overflow.beforeWrite(Long.MAX_VALUE));
    }
}
