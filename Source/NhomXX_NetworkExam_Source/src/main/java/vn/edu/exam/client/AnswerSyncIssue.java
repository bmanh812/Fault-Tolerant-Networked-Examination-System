package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;

import java.util.Objects;

/**
 * Authoritative Server information retained when a local answer cannot be reconciled in Phase 5.
 */
public record AnswerSyncIssue(
        ErrorCode errorCode,
        String questionId,
        String message,
        Payloads.AnswerView serverAnswer
) {
    public AnswerSyncIssue {
        Objects.requireNonNull(errorCode, "errorCode is required");
        requireText(questionId, "questionId");
        requireText(message, "message");
        if (errorCode == ErrorCode.VERSION_CONFLICT && serverAnswer == null) {
            throw new IllegalArgumentException("VERSION_CONFLICT requires the authoritative answer");
        }
        if (serverAnswer != null && !questionId.equals(serverAnswer.questionId())) {
            throw new IllegalArgumentException("Authoritative answer belongs to another question");
        }
    }

    public boolean requiresResync() {
        return errorCode == ErrorCode.VERSION_CONFLICT;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
    }
}
