package vn.edu.exam.server.timer;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DeadlineScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public void schedule(String sessionId, long deadlineEpochMs, Runnable autoSubmitTask) {
        long delayMs = Math.max(0L, deadlineEpochMs - System.currentTimeMillis());
        executor.schedule(autoSubmitTask, delayMs, TimeUnit.MILLISECONDS);
    }

    public void scheduleSweep(Duration interval, Runnable sweepTask) {
        executor.scheduleAtFixedRate(
                sweepTask,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
