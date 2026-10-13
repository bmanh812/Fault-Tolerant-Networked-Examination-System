package vn.edu.exam.client.ui;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Calculates a display-only countdown from a server time sample and an absolute deadline.
 * Elapsed time is measured with a monotonic source so wall-clock changes do not affect it.
 */
public final class ExamCountdown {
    private final MonotonicTimeSource timeSource;

    private boolean available;
    private long remainingAtSyncMillis;
    private long monotonicAnchorNanos;

    public ExamCountdown() {
        this(System::nanoTime);
    }

    ExamCountdown(MonotonicTimeSource timeSource) {
        this.timeSource = timeSource;
    }

    /**
     * Resets the countdown from authoritative epoch-millisecond values supplied by the server.
     * A deadline at or before server time is valid and represents an expired countdown.
     *
     * @return {@code true} when the values can be displayed, otherwise {@code false}
     */
    public synchronized boolean sync(long serverTimeEpochMillis, long deadlineEpochMillis) {
        if (serverTimeEpochMillis <= 0 || deadlineEpochMillis <= 0) {
            available = false;
            remainingAtSyncMillis = 0;
            monotonicAnchorNanos = 0;
            return false;
        }

        remainingAtSyncMillis = deadlineEpochMillis <= serverTimeEpochMillis
                ? 0
                : deadlineEpochMillis - serverTimeEpochMillis;
        monotonicAnchorNanos = timeSource.nanoTime();
        available = true;
        return true;
    }

    public synchronized OptionalLong remainingMillis() {
        if (!available) {
            return OptionalLong.empty();
        }
        long elapsedNanos = timeSource.nanoTime() - monotonicAnchorNanos;
        if (elapsedNanos <= 0) {
            return OptionalLong.of(remainingAtSyncMillis);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return OptionalLong.of(Math.max(0L, remainingAtSyncMillis - elapsedMillis));
    }

    @FunctionalInterface
    interface MonotonicTimeSource {
        long nanoTime();
    }
}
