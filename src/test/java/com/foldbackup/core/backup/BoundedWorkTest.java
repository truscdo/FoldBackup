package com.foldbackup.core.backup;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedWorkTest {
    @Test void fullWindowBackpressuresProducerAndCloseInterruptsWorkers() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), attemptingThird=new CountDownLatch(1);
        AtomicBoolean submittedThird=new AtomicBoolean(); AtomicInteger collected=new AtomicInteger();
        try(var producer=Executors.newSingleThreadExecutor()) {
            Future<?> task=producer.submit(()-> {
                try(var work=new BoundedWork<Integer>(1, value->collected.incrementAndGet())) {
                    work.submit(()->{ entered.countDown(); release.await(); return 1; });
                    work.submit(()->2); attemptingThird.countDown(); work.submit(()->3); submittedThird.set(true); work.finish();
                } catch(IOException e) { throw new RuntimeException(e); }
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(attemptingThird.await(5,TimeUnit.SECONDS));
            assertFalse(submittedThird.get()); release.countDown(); task.get(5,TimeUnit.SECONDS); assertEquals(3,collected.get());
        } finally { release.countDown(); }
        CountDownLatch worker=new CountDownLatch(1), interrupted=new CountDownLatch(1);
        try(var work=new BoundedWork<Integer>(1,value->{})) {
            work.submit(()->{ worker.countDown(); try { new CountDownLatch(1).await(); } catch(InterruptedException e) { interrupted.countDown(); throw e; } return 0; });
            assertTrue(worker.await(5,TimeUnit.SECONDS));
        }
        assertEquals(0,interrupted.getCount());
    }
}
