package vn.edu.exam.client.ui;

import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;

public enum AnswerDisplayStatus {
    UNANSWERED("Chưa chọn"),
    SAVING("Đang lưu..."),
    SAVED("Đã lưu"),
    SYNC_ERROR("Lỗi đồng bộ");

    private final String label;

    AnswerDisplayStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AnswerDisplayStatus from(
            AnswerDraft answer,
            AnswerSyncIssue syncIssue,
            boolean requestInFlight
    ) {
        if (syncIssue != null) {
            return SYNC_ERROR;
        }
        if (answer.selectedOptionId() == null) {
            return UNANSWERED;
        }
        if (answer.isSaved()) {
            return SAVED;
        }
        return requestInFlight ? SAVING : SYNC_ERROR;
    }
}
