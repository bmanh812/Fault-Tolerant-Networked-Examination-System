package vn.edu.exam.client;

import vn.edu.exam.common.protocol.ErrorCode;

/** Failure that leaves a logical submit retryable and never marks the Client as submitted. */
public final class SubmitException extends RuntimeException {
    private final ErrorCode errorCode;
    private final boolean answerSyncFailure;

    private SubmitException(
            ErrorCode errorCode,
            boolean answerSyncFailure,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.answerSyncFailure = answerSyncFailure;
    }

    static SubmitException answerSyncFailed(Throwable cause) {
        return new SubmitException(
                null,
                true,
                "Answers could not be synchronized before submit",
                cause
        );
    }

    static SubmitException serverRejected(ErrorCode errorCode, String message) {
        return new SubmitException(errorCode, false, message, null);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public boolean answerSyncFailure() {
        return answerSyncFailure;
    }
}
