package vn.edu.exam.server.service;

import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.repository.SessionRepository;

import java.time.Clock;
import java.util.Objects;

public final class AnswerService {
    private final SessionRepository repository;
    private final Clock clock;

    public AnswerService(SessionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Payloads.AnswerAck saveIfNewer(Payloads.AnswerUpdate update) {
        AnswerState committed = repository.saveAnswerIfNewer(update);
        return new Payloads.AnswerAck(
                committed.sessionId(),
                committed.questionId(),
                committed.version(),
                committed.selectedOptionId(),
                clock.millis()
        );
    }
}
