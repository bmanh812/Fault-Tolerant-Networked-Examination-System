package vn.edu.exam.common.protocol;

import java.util.List;
import java.util.Map;

final class ProtocolTestFixtures {
    static final long NOW = 1_790_800_000_000L;
    static final long DEADLINE = 1_790_803_600_000L;

    private ProtocolTestFixtures() {
    }

    static Payloads.OptionView option() {
        return new Payloads.OptionView("Q1-A", "A", "Reliable byte stream");
    }

    static Payloads.QuestionView question() {
        return new Payloads.QuestionView(
                "Q1",
                1,
                "TCP provides which service?",
                List.of(option())
        );
    }

    static Payloads.AnswerView answer() {
        return new Payloads.AnswerView("Q1", 1, "Q1-A");
    }

    static List<ProtocolMessage> validMessages() {
        return List.of(
                message(MessageType.JOIN_EXAM, new Payloads.JoinExamRequest("CAND-001", "EXAM-001")),
                message(MessageType.EXAM_STATE, new Payloads.ExamState(
                        "SESSION-001", "EXAM-001", "Network Programming", "ACTIVE",
                        DEADLINE, NOW, List.of(question()), Map.of("Q1", answer())
                )),
                message(MessageType.ANSWER_UPDATE, new Payloads.AnswerUpdate(
                        "SESSION-001", "Q1", 1, "Q1-A"
                )),
                message(MessageType.ANSWER_ACK, new Payloads.AnswerAck(
                        "SESSION-001", "Q1", 1, "Q1-A", NOW
                )),
                message(MessageType.HEARTBEAT, new Payloads.Heartbeat("SESSION-001", NOW)),
                message(MessageType.HEARTBEAT_ACK, new Payloads.HeartbeatAck(NOW, DEADLINE)),
                message(MessageType.RECONNECT, new Payloads.ReconnectRequest(
                        "SESSION-001", "CAND-001", Map.of("Q1", 1)
                )),
                message(MessageType.RESYNC_STATE, new Payloads.ResyncState(
                        "SESSION-001", "ACTIVE", DEADLINE, NOW, Map.of("Q1", answer())
                )),
                message(MessageType.SUBMIT, new Payloads.SubmitRequest("SESSION-001")),
                message(MessageType.SUBMIT_ACK, new Payloads.SubmitAck(
                        "SESSION-001", "SUBMITTED", NOW
                )),
                message(MessageType.AUTO_SUBMIT, new Payloads.AutoSubmit(
                        "SESSION-001", "AUTO_SUBMITTED", DEADLINE
                )),
                message(MessageType.ERROR, new Payloads.ErrorResponse(
                        ErrorCode.VERSION_CONFLICT, "Version conflict", answer()
                ))
        );
    }

    static ProtocolMessage message(MessageType type, Object payload) {
        return ProtocolMessage.request(type, PayloadCodec.toMap(payload));
    }
}
