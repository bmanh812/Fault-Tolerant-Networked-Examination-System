package vn.edu.exam.server.service;

import vn.edu.exam.server.model.ExamSession;

import java.util.Optional;

public interface SessionService {
    ExamSession joinExam(String candidateId, String examId);

    Optional<ExamSession> findSession(String sessionId);

    ExamSession submit(String sessionId, long submittedAt);

    ExamSession autoSubmit(String sessionId, long submittedAt);
}
