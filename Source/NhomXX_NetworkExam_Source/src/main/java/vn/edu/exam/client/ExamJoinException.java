package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ErrorCode;

import java.util.Objects;

public final class ExamJoinException extends RuntimeException {
    private final ErrorCode errorCode;

    public ExamJoinException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode is required");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
