package vn.edu.exam.common.protocol;

import java.util.List;
import java.util.Map;

public final class Payloads {
    private Payloads() {
    }

    public record JoinExamRequest(String candidateId, String examId) {
    }

    public record QuestionView(
            String questionId,
            int position,
            String content,
            List<OptionView> options
    ) {
    }

    public record OptionView(String optionId, String label, String content) {
    }

    public record AnswerView(String questionId, int version, String selectedOptionId) {
    }

    public record ExamState(
            String sessionId,
            String examId,
            String title,
            String status,
            long deadline,
            long serverTime,
            List<QuestionView> questions,
            Map<String, AnswerView> answers
    ) {
    }

    public record AnswerUpdate(
            String sessionId,
            String questionId,
            int version,
            String selectedOptionId
    ) {
    }

    public record AnswerAck(
            String sessionId,
            String questionId,
            int confirmedVersion,
            String selectedOptionId,
            long serverTime
    ) {
    }

    public record ReconnectRequest(
            String sessionId,
            String candidateId,
            Map<String, Integer> knownVersions
    ) {
    }

    public record ResyncState(
            String sessionId,
            String status,
            long deadline,
            long serverTime,
            Map<String, AnswerView> answers
    ) {
    }

    public record SubmitRequest(String sessionId) {
    }

    public record SubmitAck(String sessionId, String status, long submittedAt) {
    }

    public record AutoSubmit(String sessionId, String status, long submittedAt) {
    }

    public record Heartbeat(String sessionId, long clientTime) {
    }

    public record HeartbeatAck(long serverTime, long deadline) {
    }

    public record ErrorResponse(
            ErrorCode code,
            String message,
            AnswerView currentAnswer
    ) {
    }
}
