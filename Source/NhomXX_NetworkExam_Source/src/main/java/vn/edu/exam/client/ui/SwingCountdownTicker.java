package vn.edu.exam.client.ui;

import javax.swing.Timer;
import java.util.Objects;

/** Swing EDT-backed periodic ticker. */
final class SwingCountdownTicker implements ExamCountdownTimer.Ticker {
    private final Timer timer;
    private Runnable task = () -> {
    };

    SwingCountdownTicker(int intervalMillis) {
        timer = new Timer(intervalMillis, event -> task.run());
        timer.setCoalesce(true);
    }

    @Override
    public void start(Runnable task) {
        this.task = Objects.requireNonNull(task, "task is required");
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    @Override
    public void stop() {
        if (timer.isRunning()) {
            timer.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return timer.isRunning();
    }
}
