package io.github.truscdo.foldbackup.core.logging;

import io.github.truscdo.foldbackup.core.ProgressEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ProgressThrottlerTest {

    @Test
    void progressThrottlingAllowsStageTransitionsFirstItemAndCompletion() {
        var clock = new AtomicLong(1_000_000_000L);
        var throttler = new ProgressThrottler(clock::get, 1_000_000_000L, false);

        assertTrue(throttler.allowProgress(new ProgressEvent("Scan", 0, 4, "")));
        assertTrue(throttler.allowProgress(new ProgressEvent("Scan", 0, 4, "a.mca")));
        assertFalse(throttler.allowProgress(new ProgressEvent("Scan", 1, 4, "b.mca")));

        clock.addAndGet(1_000_000_000L);
        assertTrue(throttler.allowProgress(new ProgressEvent("Scan", 2, 4, "c.mca")));
        assertFalse(throttler.allowProgress(new ProgressEvent("Scan", 3, 4, "d.mca")));

        // 完成帧与阶段切换必须穿透时间窗
        assertTrue(throttler.allowProgress(new ProgressEvent("Scan", 4, 4, "d.mca")));
        assertTrue(throttler.allowProgress(new ProgressEvent("Commit", 0, 1, "")));
    }

    @Test
    void messageThrottlingSuppressesDuplicatesAndRespectsReset() {
        var clock = new AtomicLong(100L);
        var throttler = new ProgressThrottler(clock::get, 1_000_000_000L, true);

        assertFalse(throttler.allowMessage("", false));
        assertTrue(throttler.allowMessage("Preparing snapshot", false));

        // 即使经过很长时间，完全相同的消息也不重复刷屏
        clock.addAndGet(10_000_000_000L);
        assertFalse(throttler.allowMessage("Preparing snapshot", true));

        // 阶段切换到新文案时，force=true 可立刻穿透
        assertTrue(throttler.allowMessage("Committing snapshot", true));

        // 时间窗未满且 force=false 时拦住非强制消息
        assertFalse(throttler.allowMessage("Finalizing", false));

        throttler.reset();
        assertTrue(throttler.allowMessage("Preparing snapshot", false));
    }
}
