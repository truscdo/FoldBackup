package io.github.truscdo.foldbackup.core.archive;

import io.github.truscdo.foldbackup.core.format.StreamIo;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Completion-first writing, falling back to streaming only when producers wait for buffer space. */
final class OrderedCompressionPipeline {
    @FunctionalInterface interface Producer<T> { T run(int index, OutputStream output) throws IOException; }
    @FunctionalInterface interface StreamingWriter<T> { void write(int index, Result<T> result) throws IOException; }

    record Metrics(long peakBufferBytes, int peakInFlight, long bufferWaitNanos, long writerWaitNanos,
            long inFlightWaitNanos, long writerBusyNanos, long tailNanos, long ioWaitNanos) {}

    static <T> Metrics run(int count, int workers, int window, int blockSize, long budget,
            Producer<T> producer, StreamingWriter<T> writer) throws IOException {
        if (count < 0 || workers < 1 || window < 1 || blockSize < 1 || budget / blockSize < 2)
            throw new IllegalArgumentException("Invalid pipeline limits (at least two blocks required)");
        var state = new State<T>(count, window, blockSize, (int) Math.min(Integer.MAX_VALUE, budget / blockSize));
        var io = new io.github.truscdo.foldbackup.core.format.IoBudget(Math.min(workers, 2));
        var pool = Executors.newFixedThreadPool(Math.min(workers, Math.max(1, count)));
        try {
            for (int i = 0; i < Math.min(workers, count); i++) {
                pool.execute(() -> {
                    try (var scope = io.enter()) {
                        Entry<T> entry;
                        while ((entry = state.claim()) != null) {
                            try (var output = new BlockOutput<T>(state, entry)) {
                                T summary = producer.run(entry.index, output);
                                output.flush();
                                synchronized (state) {
                                    state.check();
                                    entry.summary = summary;
                                    entry.done = true;
                                    state.notifyAll();
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        state.fail(failure);
                    }
                });
            }
            for (int written = 0; written < count; written++) {
                Entry<T> selected = state.awaitEntry();
                int index = selected.index;
                Result<T> result = new Result<>(state, selected);
                long writeStart = System.nanoTime(), waitBefore = state.writerWaitNanos;
                try (var scope = io.enter()) { writer.write(index, result); }
                synchronized (state) {
                    state.writerBusyNanos += System.nanoTime() - writeStart - (state.writerWaitNanos - waitBefore);
                    state.check();
                    if (!result.drained) throw new IOException("Writer did not consume the region completely");
                    state.entries.remove(index);
                    state.current = -1;
                    if (state.next == count && state.entries.size() < workers && state.tailStart == 0)
                        state.tailStart = System.nanoTime();
                    state.notifyAll(); // transfers reserve and releases the in-flight permit together
                }
            }
            return state.metrics(io.waitNanos());
        } catch (IOException | RuntimeException | Error failure) {
            state.fail(failure);
            throw failure;
        } finally {
            state.stop();
            pool.shutdownNow();
            // close joins even when the caller is interrupted, restoring its interrupt flag.
            pool.close();
            synchronized (state) {
                state.entries.clear();
                state.free.clear();
            }
        }
    }

    private static final class Block {
        final byte[] bytes;
        int length;
        Block(int size) { bytes = new byte[size]; }
    }

    private static final class Entry<T> {
        final int index;
        final ArrayDeque<Block> blocks = new ArrayDeque<>();
        boolean done;
        T summary;
        Entry(int index) { this.index = index; }
    }

    private static final class State<T> {
        final int count, window, blockSize, maxBlocks;
        final Map<Integer, Entry<T>> entries = new HashMap<>();
        final ArrayDeque<Block> free = new ArrayDeque<>();
        int next, used, allocated, peakInFlight;
        int current = -1;
        int bufferWaiters;
        long bufferWaitNanos, writerWaitNanos;
        long inFlightWaitNanos, writerBusyNanos, tailStart;
        Throwable failure;
        boolean stopped;

        State(int count, int window, int blockSize, int maxBlocks) {
            this.count = count; this.window = window; this.blockSize = blockSize; this.maxBlocks = maxBlocks;
        }

        void check() throws IOException {
            if (failure != null) throw new IOException("Archive recompression failed", failure);
            StreamIo.checkCancelled();
            if (stopped) throw new IOException("Archive creation stopped");
        }

        void await() throws IOException {
            try { wait(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Archive creation interrupted", e);
            }
        }

        synchronized Entry<T> claim() throws IOException {
            while (true) {
                check();
                if (next == count) return null;
                if (entries.size() < window) {
                    // Permit acquisition and FIFO removal are atomic: the head is always assigned first.
                    Entry<T> entry = new Entry<>(next++);
                    entries.put(entry.index, entry);
                    peakInFlight = Math.max(peakInFlight, entries.size());
                    notifyAll();
                    return entry;
                }
                long start = System.nanoTime();
                await();
                inFlightWaitNanos += System.nanoTime() - start;
            }
        }

        synchronized Entry<T> awaitEntry() throws IOException {
            while (true) {
                check();
                Entry<T> selected = null;
                for (Entry<T> entry : entries.values()) {
                    if (entry.done) { selected = entry; break; }
                    // A notified producer may not have reacquired the monitor yet; require pressure too.
                    if (bufferWaiters > 0 && used >= maxBlocks - 1
                            && selected == null && !entry.blocks.isEmpty()) selected = entry;
                }
                if (selected != null) {
                    current = selected.index;
                    notifyAll();
                    return selected;
                }
                long start = System.nanoTime();
                await();
                writerWaitNanos += System.nanoTime() - start;
            }
        }

        synchronized Block acquire(int index) throws IOException {
            boolean waiting = false;
            try {
                while (true) {
                    check();
                    // All leased blocks count, including producer partials and the writer's current block.
                    // Inactive producers leave one slot free for the selected entry.
                    if (used < maxBlocks && (index == current || used < maxBlocks - 1)) {
                        Block block = free.pollFirst();
                        if (block == null) { block = new Block(blockSize); allocated++; }
                        used++;
                        return block;
                    }
                    if (!waiting) {
                        waiting = true;
                        bufferWaiters++;
                        // Notify only on entering the wait, so blocked producers do not wake each other repeatedly.
                        notifyAll();
                    }
                    long start = System.nanoTime();
                    try {
                        await();
                    } finally {
                        bufferWaitNanos += System.nanoTime() - start;
                    }
                }
            } finally {
                if (waiting) bufferWaiters--;
            }
        }

        synchronized void release(Block block) {
            block.length = 0;
            used--;
            free.addLast(block);
            notifyAll();
        }

        synchronized void fail(Throwable cause) {
            if (failure == null) failure = cause;
            notifyAll();
        }

        synchronized void stop() { stopped = true; notifyAll(); }
        synchronized Metrics metrics(long ioWaitNanos) {
            return new Metrics((long) allocated * blockSize, peakInFlight, bufferWaitNanos, writerWaitNanos,
                    inFlightWaitNanos, writerBusyNanos, tailStart == 0 ? 0 : System.nanoTime() - tailStart, ioWaitNanos);
        }
    }

    private static final class BlockOutput<T> extends OutputStream {
        final State<T> state;
        final Entry<T> entry;
        BlockOutput(State<T> state, Entry<T> entry) { this.state = state; this.entry = entry; }

        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            while (length > 0) {
                synchronized (state) {
                    state.check();
                    // A queued partial block is immediately selectable, but may
                    // still be filled until the writer removes it under this lock.
                    Block tail = entry.blocks.peekLast();
                    if (tail == null || tail.length == tail.bytes.length) {
                        tail = state.acquire(entry.index);
                        entry.blocks.addLast(tail);
                    }
                    int n = Math.min(length, tail.bytes.length - tail.length);
                    System.arraycopy(bytes, offset, tail.bytes, tail.length, n);
                    tail.length += n; offset += n; length -= n;
                    state.notifyAll();
                }
            }
        }
        @Override public void flush() throws IOException {
            synchronized (state) { state.check(); }
        }
        @Override public void close() {
            // Every block belongs to the state; failure cleanup follows worker join.
        }
    }

    static final class Result<T> {
        private final State<T> state;
        private final Entry<T> entry;
        private boolean drained;
        private Result(State<T> state, Entry<T> entry) { this.state = state; this.entry = entry; }

        /** Must be consumed once, on the archive writer thread. Completion never occupies a block. */
        T drainTo(OutputStream output) throws IOException {
            if (drained) throw new IOException("Region already consumed");
            while (true) {
                Block block;
                synchronized (state) {
                    while (true) {
                        state.check();
                        block = entry.blocks.pollFirst();
                        if (block != null) break;
                        if (entry.done) { drained = true; return entry.summary; }
                        long start = System.nanoTime();
                        state.await();
                        state.writerWaitNanos += System.nanoTime() - start;
                    }
                }
                try { output.write(block.bytes, 0, block.length); }
                finally { state.release(block); }
            }
        }
    }

    private OrderedCompressionPipeline() {}
}
