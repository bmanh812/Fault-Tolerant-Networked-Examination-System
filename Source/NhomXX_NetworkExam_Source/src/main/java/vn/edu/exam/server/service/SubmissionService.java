package vn.edu.exam.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.SessionStatus;
import vn.edu.exam.server.repository.SessionRepository;
import vn.edu.exam.server.timer.DeadlineScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public final class SubmissionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionService.class);
    private final SessionRepository repository;
    private final DeadlineScheduler scheduler;
    private final Consumer<Payloads.AutoSubmit> autoSubmitListener;

    public SubmissionService(
            SessionRepository repository,
            DeadlineScheduler scheduler,
            Consumer<Payloads.AutoSubmit> autoSubmitListener
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
        this.autoSubmitListener = Objects.requireNonNull(autoSubmitListener, "listener is required");
    }

    public Payloads.SubmitAck submit(String sessionId) {
        SessionRepository.FinalizedSession result = repository.submit(sessionId);
        if (result.status() == SessionStatus.AUTO_SUBMITTED) {
            if (result.newlyFinalized()) {
                notifyAutoSubmit(result);
            }
            throw new ServerFailure(
                    result.newlyFinalized() ? ErrorCode.EXAM_ENDED : ErrorCode.ALREADY_SUBMITTED,
                    "The exam session was automatically submitted"
            );
        }
        return new Payloads.SubmitAck(result.sessionId(), result.status().name(), result.submittedAt());
    }

    public void schedule(String sessionId, long deadline) {
        scheduler.schedule(sessionId, deadline, () -> {
            try {
                autoSubmitIfExpired(sessionId);
            } catch (RuntimeException error) {
                LOGGER.error("Auto-submit failed for session {}", sessionId, error);
            }
        });
    }

    public void startSweep(Duration interval) {
        sweepExpired();
        scheduler.scheduleSweep(interval, () -> {
            try {
                sweepExpired();
            } catch (RuntimeException error) {
                LOGGER.error("Deadline sweep failed", error);
            }
        });
    }

    public void sweepExpired() {
        for (String sessionId : repository.findExpiredSessionIds()) {
            autoSubmitIfExpired(sessionId);
        }
    }

    public void notifyIfAlreadyAutoSubmitted(String sessionId, Consumer<Payloads.AutoSubmit> listener) {
        repository.findFinalized(sessionId)
                .filter(result -> result.status() == SessionStatus.AUTO_SUBMITTED)
                .ifPresent(result -> listener.accept(toEvent(result)));
    }

    private void autoSubmitIfExpired(String sessionId) {
        repository.autoSubmitIfExpired(sessionId).ifPresent(this::notifyAutoSubmit);
    }

    private void notifyAutoSubmit(SessionRepository.FinalizedSession result) {
        autoSubmitListener.accept(toEvent(result));
    }

    private static Payloads.AutoSubmit toEvent(SessionRepository.FinalizedSession result) {
        return new Payloads.AutoSubmit(result.sessionId(), result.status().name(), result.submittedAt());
    }
}
