package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;

public interface ExamUiView {
    void showJoinInProgress();

    void showJoinError(String message);

    void showExam(ClientExamState.Snapshot exam);

    void showAnswerState(String questionId, AnswerDraft answer, AnswerDisplayStatus status);

    void showAnswerStatus(String questionId, AnswerDisplayStatus status);

    void showConnectionState(ConnectionDisplayStatus status);

    void setAnsweringEnabled(boolean enabled);
}
