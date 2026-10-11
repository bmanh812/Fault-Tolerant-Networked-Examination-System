package vn.edu.exam.server.service;

import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.Payloads;

import java.util.Objects;

public final class ServerFailure extends RuntimeException {
    private final ErrorCode code;
    private final Payloads.AnswerView currentAnswer;

    public ServerFailure(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ServerFailure(ErrorCode code, String message, Payloads.AnswerView currentAnswer) {
        this(code, message, currentAnswer, null);
    }

    public ServerFailure(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    private ServerFailure(ErrorCode code, String message, Payloads.AnswerView currentAnswer, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code is required");
        this.currentAnswer = currentAnswer;
    }

    public ErrorCode code() {
        return code;
    }

    public Payloads.AnswerView currentAnswer() {
        return currentAnswer;
    }
}
