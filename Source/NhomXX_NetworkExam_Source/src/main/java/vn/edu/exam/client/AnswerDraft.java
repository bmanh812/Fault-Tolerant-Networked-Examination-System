package vn.edu.exam.client;

public record AnswerDraft(
        String questionId,
        int localVersion,
        int confirmedVersion,
        String selectedOptionId,
        String pendingRequestId
) {
    public boolean isConfirmed() {
        return confirmedVersion >= localVersion;
    }
}
