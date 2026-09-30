package io.github.truscdo.foldbackup.core.format;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IoBudgetTest {
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    @Test void scopeDoesNotHoldPermitDuringComputationAndNestedAdaptersDoNotDeadlock() throws Exception {
        var budget = new IoBudget(1);
        var firstComputing = new CountDownLatch(1);
        var secondRead = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                try (var scope = budget.enter()) { firstComputing.countDown(); await(secondRead); }
                return 1;
            });
            var second = pool.submit(() -> {
                await(firstComputing);
                try (var scope = budget.enter()) {
                    return IoBudget.call(() -> IoBudget.call(() -> { secondRead.countDown(); return 2; }));
                }
            });
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, second.get(5, TimeUnit.SECONDS));
        }
    }
    @Test void cancelledWaiterExitsWithoutLeakingPermit() throws Exception {
        var budget = new IoBudget(1);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondExited = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                try (var scope = budget.enter()) {
                    return IoBudget.call(() -> { held.countDown(); await(release); return 1; });
                }
            });
            try {
                await(held);
                var second = pool.submit(() -> {
                    try (var scope = budget.enter()) {
                        secondStarted.countDown();
                        return IoBudget.call(calls::incrementAndGet);
                    } finally { secondExited.countDown(); }
                });
                await(secondStarted);
                second.cancel(true);
                await(secondExited);
                assertEquals(0, calls.get());
            } finally { release.countDown(); }
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            try (var scope = budget.enter()) { assertEquals(1, IoBudget.call(calls::incrementAndGet)); }
        }
    }
}
