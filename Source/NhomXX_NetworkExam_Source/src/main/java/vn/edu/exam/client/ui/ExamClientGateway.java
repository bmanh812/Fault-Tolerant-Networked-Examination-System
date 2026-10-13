package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ExamSubmission;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ExamClientGateway extends AutoCloseable {
    void connect(String host, int port) throws IOException;

    CompletableFuture<ClientExamState.Snapshot> joinExam(String candidateId, String examId);

    CompletableFuture<AnswerDraft> updateAnswer(String questionId, String optionId);

    AnswerDraft selectAnswer(String questionId, String optionId);

    CompletableFuture<AnswerDraft> sendAnswerUpdate(String questionId);

    void beginSubmission();

    CompletableFuture<ExamSubmission> submitExam();

    ClientExamState.Snapshot snapshot();

    ClientState state();

    Optional<AnswerSyncIssue> answerSyncIssue(String questionId);

    boolean answerUpdateInFlight(String questionId);

    boolean submitRetryAvailable();

    boolean submitRequestSent();

    default boolean isConnected() {
        return switch (state()) {
            case CONNECTED, JOINING, ACTIVE, SUBMITTING, SUBMITTED, AUTO_SUBMITTED -> true;
            default -> false;
        };
    }

    default void setSubmissionHandler(Consumer<ExamSubmission> handler) {
    }

    @Override
    void close() throws IOException;
}
