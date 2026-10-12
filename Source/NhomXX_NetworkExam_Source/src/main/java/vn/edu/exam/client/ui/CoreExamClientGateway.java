package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ExamClient;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class CoreExamClientGateway implements ExamClientGateway {
    private final ExamClient client;

    public CoreExamClientGateway(ExamClient client) {
        this.client = Objects.requireNonNull(client, "client is required");
    }

    @Override
    public void connect(String host, int port) throws IOException {
        client.connect(host, port);
    }

    @Override
    public CompletableFuture<ClientExamState.Snapshot> joinExam(String candidateId, String examId) {
        return client.joinExam(candidateId, examId);
    }

    @Override
    public CompletableFuture<AnswerDraft> updateAnswer(String questionId, String optionId) {
        return client.updateAnswer(questionId, optionId);
    }

    @Override
    public ClientExamState.Snapshot snapshot() {
        return client.examState().snapshot();
    }

    @Override
    public ClientState state() {
        return client.state();
    }

    @Override
    public Optional<AnswerSyncIssue> answerSyncIssue(String questionId) {
        return client.answerSyncIssue(questionId);
    }

    @Override
    public boolean answerUpdateInFlight(String questionId) {
        return client.answerUpdateInFlight(questionId);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
