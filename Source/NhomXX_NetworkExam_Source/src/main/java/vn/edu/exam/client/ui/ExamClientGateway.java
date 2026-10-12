package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ExamClientGateway extends AutoCloseable {
    void connect(String host, int port) throws IOException;

    CompletableFuture<ClientExamState.Snapshot> joinExam(String candidateId, String examId);

    CompletableFuture<AnswerDraft> updateAnswer(String questionId, String optionId);

    ClientExamState.Snapshot snapshot();

    ClientState state();

    Optional<AnswerSyncIssue> answerSyncIssue(String questionId);

    boolean answerUpdateInFlight(String questionId);

    @Override
    void close() throws IOException;
}
