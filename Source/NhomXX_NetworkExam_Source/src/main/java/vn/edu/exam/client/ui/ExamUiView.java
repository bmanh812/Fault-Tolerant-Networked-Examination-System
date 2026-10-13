package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;

public interface ExamUiView {
    void showJoinInProgress();

    void showJoinError(String message);

    void showExam(ClientExamState.Snapshot exam);

    /** Lifecycle hook for EXAM_STATE now and RESYNC_STATE in a later phase. */
    default void syncCountdown(long serverTimeEpochMillis, long deadlineEpochMillis) {
    }

    /** Stops display updates when the UI/client closes or a future final state is applied. */
    default void stopCountdown() {
    }

    void showAnswerState(String questionId, AnswerDraft answer, AnswerDisplayStatus status);

    void showAnswerStatus(String questionId, AnswerDisplayStatus status);

    void showConnectionState(ConnectionDisplayStatus status);

    void setAnsweringEnabled(boolean enabled);
}
