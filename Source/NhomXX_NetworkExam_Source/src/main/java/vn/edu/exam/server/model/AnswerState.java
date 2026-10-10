package vn.edu.exam.server.model;

public record AnswerState(
        String sessionId,
        String questionId,
        int version,
        String selectedOptionId,
        long updatedAt
) {
}
