package com.foldbackup.core.backup;

/**
 * Small, deterministic resource-budget helpers for the online region scan.
 *
 * <p>The online backup shares the JVM with Minecraft, so automatic values are
 * deliberately based on the resources visible to this JVM instead of using a
 * fixed worker count or a fixed byte limit.</p>
 */
public final class BackupResourceBudget {

    private static final long MEBIBYTE = 1L << 20;
    private static final long AUTO_MEMORY_PERCENT = 20;
    private static final long AUTO_HEADROOM_PERCENT = 50;

    private BackupResourceBudget() {
    }

    /** Conservative level-based allowance, including native compression state; independent of input size. */
    public static long streamingTaskBytes(int level) {
        return (level >= 20 ? 768L : level >= 17 ? 256L : level >= 10 ? 96L : 32L) * MEBIBYTE;
    }

    /**
     * Calculate the number of CPU workers. A requested value of zero means
     * automatic mode and uses roughly half of the processors visible to the
     * JVM, leaving the other half for Minecraft and its own workers.
     */
    public static int cpuWorkers(int requested, int availableProcessors, int workItems) {
        int processors = Math.max(1, availableProcessors);
        int items = Math.max(1, workItems);
        int automatic = Math.max(1, processors / 2);
        int selected = requested > 0 ? requested : automatic;
        return Math.max(1, Math.min(selected, Math.min(processors, items)));
    }

    /**
     * Calculate the total memory budget for in-flight region work.
     * A requested value of zero uses both a fraction of Xmx and a fraction of
     * the currently unused heap, whichever is smaller.
     */
    public static long memoryBudgetBytes(long requestedMebibytes,
            long maxHeapBytes, long usedHeapBytes) {
        long maxHeap = Math.max(1, maxHeapBytes);
        long usedHeap = Math.max(0, Math.min(usedHeapBytes, maxHeap));
        long headroom = maxHeap - usedHeap;

        if (requestedMebibytes > 0) {
            return Math.max(MEBIBYTE, Math.min(requestedMebibytes * MEBIBYTE, headroom));
        }

        long xmxBudget = percent(maxHeap, AUTO_MEMORY_PERCENT);
        long headroomBudget = percent(headroom, AUTO_HEADROOM_PERCENT);
        return Math.max(MEBIBYTE, Math.min(xmxBudget, headroomBudget));
    }

    private static long percent(long value, long percentage) {
        if (value <= 0) {
            return 1;
        }
        if (value > Long.MAX_VALUE / percentage) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, (value * percentage) / 100);
    }
}
