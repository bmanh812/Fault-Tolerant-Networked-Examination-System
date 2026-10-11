package vn.edu.exam.server.repository;

import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.model.ExamSession;
import vn.edu.exam.server.model.SessionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SessionRepository {
    record JoinedExam(
            ExamSession session,
            String title,
            List<Payloads.QuestionView> questions,
            Map<String, AnswerState> answers
    ) {
    }

    record FinalizedSession(String sessionId, SessionStatus status, long submittedAt, boolean newlyFinalized) {
    }

    JoinedExam joinExam(String candidateId, String examId);

    Optional<ExamSession> findById(String sessionId);

    Map<String, AnswerState> findAnswers(String sessionId);

    /** Returns only after the new answer has committed, or after an exact duplicate is confirmed. */
    AnswerState saveAnswerIfNewer(Payloads.AnswerUpdate update);

    FinalizedSession submit(String sessionId);

    Optional<FinalizedSession> autoSubmitIfExpired(String sessionId);

    List<String> findExpiredSessionIds();

    Optional<FinalizedSession> findFinalized(String sessionId);
}
