package vn.edu.exam.server.service;

import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.repository.SessionRepository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SessionService {
    private final SessionRepository repository;
    private final Clock clock;

    public SessionService(SessionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public Payloads.ExamState joinExam(String candidateId, String examId) {
        SessionRepository.JoinedExam joined = repository.joinExam(candidateId, examId);
        Map<String, Payloads.AnswerView> answers = new LinkedHashMap<>();
        for (AnswerState answer : joined.answers().values()) {
            answers.put(answer.questionId(), new Payloads.AnswerView(
                    answer.questionId(), answer.version(), answer.selectedOptionId()
            ));
        }
        return new Payloads.ExamState(
                joined.session().sessionId(),
                joined.session().examId(),
                joined.title(),
                joined.session().status().name(),
                joined.session().deadline(),
                clock.millis(),
                joined.questions(),
                answers
        );
    }
}
