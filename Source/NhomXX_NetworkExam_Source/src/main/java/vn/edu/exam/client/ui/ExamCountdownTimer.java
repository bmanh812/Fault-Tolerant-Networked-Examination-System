package vn.edu.exam.client.ui;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** Coordinates countdown calculation, periodic refresh, formatting and lifecycle. */
public final class ExamCountdownTimer implements AutoCloseable {
    private final ExamCountdown countdown;
    private final Ticker ticker;
    private final Consumer<Display> displaySink;

    public ExamCountdownTimer(
            ExamCountdown countdown,
            Ticker ticker,
            Consumer<Display> displaySink
    ) {
        this.countdown = Objects.requireNonNull(countdown, "countdown is required");
        this.ticker = Objects.requireNonNull(ticker, "ticker is required");
        this.displaySink = Objects.requireNonNull(displaySink, "display sink is required");
    }

    /**
     * Starts or resets display time from a new authoritative server sample. Calling this while
     * already running resets the monotonic anchor without creating another periodic ticker.
     */
    public void sync(long serverTimeEpochMillis, long deadlineEpochMillis) {
        if (!countdown.sync(serverTimeEpochMillis, deadlineEpochMillis)) {
            stop();
            displaySink.accept(new Display("--:--", State.UNAVAILABLE));
            return;
        }

        refresh();
        OptionalLong remaining = countdown.remainingMillis();
        if (remaining.isPresent() && remaining.getAsLong() > 0 && !ticker.isRunning()) {
            ticker.start(this::refresh);
        }
    }

    /** Lightweight refresh intended for a UI timer callback. */
    public void refresh() {
        OptionalLong remaining = countdown.remainingMillis();
        if (remaining.isEmpty()) {
            stop();
            displaySink.accept(new Display("--:--", State.UNAVAILABLE));
            return;
        }

        long remainingMillis = remaining.getAsLong();
        if (remainingMillis == 0) {
            stop();
            displaySink.accept(new Display("00:00", State.EXPIRED_WAITING_FOR_SERVER));
            return;
        }
        displaySink.accept(new Display(format(remainingMillis), State.RUNNING));
    }

    public void stop() {
        if (ticker.isRunning()) {
            ticker.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    static String format(long remainingMillis) {
        long totalSeconds = remainingMillis <= 0
                ? 0
                : 1 + ((remainingMillis - 1) / 1_000);
        long hours = totalSeconds / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
        }
        return "%02d:%02d".formatted(minutes, seconds);
    }

    public enum State {
        RUNNING,
        EXPIRED_WAITING_FOR_SERVER,
        UNAVAILABLE
    }

    public record Display(String text, State state) {
    }

    public interface Ticker {
        void start(Runnable task);

        void stop();

        boolean isRunning();
    }
}
