package io.github.truscdo.foldbackup.core.format;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

/** Optional task-local disk-operation budget. Permits never span compression callbacks. */
public final class IoBudget {
    private static final ThreadLocal<IoBudget> CURRENT = new ThreadLocal<>();
    private final Semaphore permits;
    private final ThreadLocal<Boolean> held = ThreadLocal.withInitial(() -> false);
    private final LongAdder waitNanos = new LongAdder();
    public IoBudget(int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("I/O concurrency must be positive");
        permits = new Semaphore(concurrency, true);
    }
    public Scope enter() {
        IoBudget previous = CURRENT.get();
        CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    @FunctionalInterface public interface Scope extends AutoCloseable { void close(); }
    @FunctionalInterface public interface Operation<T> { T run() throws IOException; }
    public static <T> T call(Operation<T> operation) throws IOException {
        IoBudget budget = CURRENT.get();
        if (budget == null || budget.held.get()) return operation.run();
        long start = System.nanoTime();
        try { budget.permits.acquire(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("I/O wait interrupted");
        }
        budget.waitNanos.add(System.nanoTime() - start);
        budget.held.set(true);
        try { return operation.run(); }
        finally { budget.held.remove(); budget.permits.release(); }
    }
    public long waitNanos() { return waitNanos.sum(); }
}
