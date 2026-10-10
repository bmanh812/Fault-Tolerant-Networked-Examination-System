package vn.edu.exam.server.model;

public record ExamSession(
        String sessionId,
        String examId,
        String candidateId,
        SessionStatus status,
        long startedAt,
        long deadline
) {
}
