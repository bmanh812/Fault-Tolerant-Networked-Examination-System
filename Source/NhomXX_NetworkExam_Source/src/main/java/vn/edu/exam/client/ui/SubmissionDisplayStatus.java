package vn.edu.exam.client.ui;

public enum SubmissionDisplayStatus {
    READY("Bài thi đang diễn ra."),
    SYNCHRONIZING("Đang đồng bộ đáp án..."),
    SUBMITTING("Đang nộp bài..."),
    RETRYABLE_ERROR("Chưa thể xác nhận nộp bài. Có thể thử lại."),
    SUBMITTED("Đã nộp bài"),
    AUTO_SUBMITTED("Hết thời gian - bài đã được tự động nộp");

    private final String label;

    SubmissionDisplayStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
