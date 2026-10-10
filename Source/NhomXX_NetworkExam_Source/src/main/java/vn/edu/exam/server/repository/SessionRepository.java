package vn.edu.exam.server.repository;

import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.model.ExamSession;

import java.util.Map;
import java.util.Optional;

public interface SessionRepository {
    ExamSession create(ExamSession session);

    Optional<ExamSession> findById(String sessionId);

    Map<String, AnswerState> findAnswers(String sessionId);

    AnswerState saveAnswerIfNewer(AnswerState answer);

    ExamSession save(ExamSession session);
}
