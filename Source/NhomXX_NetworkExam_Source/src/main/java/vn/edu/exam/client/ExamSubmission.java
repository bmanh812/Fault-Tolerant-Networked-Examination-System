package vn.edu.exam.client;

/** Immutable final submission state received from the authoritative Server. */
public record ExamSubmission(
        String sessionId,
        ClientState state,
        long submittedAt
) {
    public ExamSubmission {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must be a non-blank string");
        }
        if (state != ClientState.SUBMITTED && state != ClientState.AUTO_SUBMITTED) {
            throw new IllegalArgumentException("Submission state must be final");
        }
        if (submittedAt <= 0) {
            throw new IllegalArgumentException("submittedAt must be positive");
        }
    }

    public boolean autoSubmitted() {
        return state == ClientState.AUTO_SUBMITTED;
    }
}
