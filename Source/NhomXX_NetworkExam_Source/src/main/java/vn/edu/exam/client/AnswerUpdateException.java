package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;

import java.util.Objects;

public final class AnswerUpdateException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String questionId;
    private final Payloads.AnswerView serverAnswer;

    public AnswerUpdateException(
            ErrorCode errorCode,
            String questionId,
            String message,
            Payloads.AnswerView serverAnswer
    ) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode is required");
        this.questionId = Objects.requireNonNull(questionId, "questionId is required");
        this.serverAnswer = serverAnswer;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String questionId() {
        return questionId;
    }

    public Payloads.AnswerView serverAnswer() {
        return serverAnswer;
    }

    public boolean requiresResync() {
        return errorCode == ErrorCode.VERSION_CONFLICT;
    }
}
