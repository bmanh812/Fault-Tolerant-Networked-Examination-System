package vn.edu.exam.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamCountdownTimerTest {
    private final FakeMonotonicTime time = new FakeMonotonicTime();
    private final FakeTicker ticker = new FakeTicker();
    private final List<ExamCountdownTimer.Display> displays = new ArrayList<>();
    private final ExamCountdownTimer timer = new ExamCountdownTimer(
            new ExamCountdown(time), ticker, displays::add
    );

    @Test
    void syncStartsOneTickerAndPublishesFormattedCountdown() {
        timer.sync(1_000_000L, 2_800_000L);

        assertTrue(ticker.running);
        assertEquals(1, ticker.startCalls);
        assertEquals(new ExamCountdownTimer.Display(
                "30:00", ExamCountdownTimer.State.RUNNING
        ), lastDisplay());
    }

    @Test
    void resyncWhileRunningDoesNotCreateDuplicateTicker() {
        timer.sync(1_000_000L, 1_030_000L);
        time.advanceSeconds(10);
        ticker.tick();
        assertEquals("00:20", lastDisplay().text());

        timer.sync(2_000_000L, 2_060_000L);

        assertEquals(1, ticker.startCalls);
        assertEquals("01:00", lastDisplay().text());
        time.advanceSeconds(5);
        ticker.tick();
        assertEquals("00:55", lastDisplay().text());
    }

    @Test
    void elapsedPauseIsCalculatedFromMonotonicTimeNotTickCount() {
        timer.sync(1_000_000L, 1_030_000L);

        time.advanceSeconds(12);
        ticker.tick();

        assertEquals("00:18", lastDisplay().text());
    }

    @Test
    void reachingZeroStopsTickerAndWaitsForServer() {
        timer.sync(1_000_000L, 1_005_000L);

        time.advanceSeconds(6);
        ticker.tick();

        assertFalse(ticker.running);
        assertEquals(new ExamCountdownTimer.Display(
                "00:00", ExamCountdownTimer.State.EXPIRED_WAITING_FOR_SERVER
        ), lastDisplay());
    }

    @Test
    void expiredDeadlineNeverStartsTicker() {
        timer.sync(1_000_000L, 900_000L);

        assertFalse(ticker.running);
        assertEquals(0, ticker.startCalls);
        assertEquals(ExamCountdownTimer.State.EXPIRED_WAITING_FOR_SERVER, lastDisplay().state());
    }

    @Test
    void invalidTimeDisplaysUnavailableWithoutCrashing() {
        timer.sync(0, 0);

        assertFalse(ticker.running);
        assertEquals(new ExamCountdownTimer.Display(
                "--:--", ExamCountdownTimer.State.UNAVAILABLE
        ), lastDisplay());
    }

    @Test
    void stopAndCloseAreIdempotent() {
        timer.sync(1_000_000L, 1_030_000L);

        timer.stop();
        timer.stop();
        timer.close();

        assertFalse(ticker.running);
        assertEquals(1, ticker.stopCalls);
    }

    @Test
    void formatterUsesHoursOnlyWhenNeededAndRoundsUpPartialSecond() {
        assertEquals("00:00", ExamCountdownTimer.format(0));
        assertEquals("00:01", ExamCountdownTimer.format(1));
        assertEquals("59:59", ExamCountdownTimer.format(3_599_000L));
        assertEquals("01:00:00", ExamCountdownTimer.format(3_600_000L));
        assertEquals("01:29:58", ExamCountdownTimer.format(5_398_000L));
    }

    private ExamCountdownTimer.Display lastDisplay() {
        return displays.get(displays.size() - 1);
    }

    private static final class FakeMonotonicTime implements ExamCountdown.MonotonicTimeSource {
        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        private void advanceSeconds(long seconds) {
            nanos += TimeUnit.SECONDS.toNanos(seconds);
        }
    }

    private static final class FakeTicker implements ExamCountdownTimer.Ticker {
        private Runnable task;
        private boolean running;
        private int startCalls;
        private int stopCalls;

        @Override
        public void start(Runnable task) {
            this.task = task;
            if (!running) {
                running = true;
                startCalls++;
            }
        }

        @Override
        public void stop() {
            if (running) {
                running = false;
                stopCalls++;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        private void tick() {
            task.run();
        }
    }
}
