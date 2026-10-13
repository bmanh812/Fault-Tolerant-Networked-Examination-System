package vn.edu.exam.client.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamCountdownTest {
    private final FakeMonotonicTime time = new FakeMonotonicTime();
    private final ExamCountdown countdown = new ExamCountdown(time);

    @Test
    void syncUsesDifferenceBetweenServerTimeAndDeadline() {
        assertTrue(countdown.sync(1_000_000L, 2_800_000L));

        assertEquals(1_800_000L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void elapsedMonotonicTimeReducesRemainingDuration() {
        countdown.sync(1_000_000L, 2_800_000L);

        time.advanceSeconds(10);

        assertEquals(1_790_000L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void equalDeadlineIsAlreadyExpired() {
        countdown.sync(1_000_000L, 1_000_000L);

        assertEquals(0L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void pastDeadlineIsClampedToZero() {
        countdown.sync(1_000_000L, 900_000L);

        time.advanceSeconds(20);

        assertEquals(0L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void elapsedTimeLargerThanInitialDurationNeverMakesCountdownNegative() {
        countdown.sync(1_000_000L, 1_005_000L);

        time.advanceSeconds(60);

        assertEquals(0L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void wallClockIsNotConsultedAfterSync() {
        countdown.sync(1_000_000L, 1_030_000L);

        // Only the injected monotonic source advances; changing a wall clock cannot enter this model.
        time.advanceSeconds(3);

        assertEquals(27_000L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void resyncReplacesDurationAndMonotonicAnchor() {
        countdown.sync(1_000_000L, 1_030_000L);
        time.advanceSeconds(10);
        assertEquals(20_000L, countdown.remainingMillis().orElseThrow());

        countdown.sync(2_000_000L, 2_060_000L);
        assertEquals(60_000L, countdown.remainingMillis().orElseThrow());

        time.advanceSeconds(5);
        assertEquals(55_000L, countdown.remainingMillis().orElseThrow());
    }

    @Test
    void invalidOrMissingEpochValuesMakeCountdownUnavailable() {
        assertFalse(countdown.sync(0, 1_000_000L));
        assertTrue(countdown.remainingMillis().isEmpty());

        assertFalse(countdown.sync(1_000_000L, 0));
        assertTrue(countdown.remainingMillis().isEmpty());
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
}
