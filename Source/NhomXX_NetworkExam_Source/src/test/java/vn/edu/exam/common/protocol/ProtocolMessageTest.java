package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolMessageTest {
    @Test
    void responseKeepsRequestId() {
        ProtocolMessage request = ProtocolMessage.request(
                MessageType.JOIN_EXAM,
                Map.of("candidateId", "CAND-001", "examId", "EXAM-001")
        );
        ProtocolMessage response = ProtocolMessage.response(
                MessageType.EXAM_STATE,
                request,
                Map.of("sessionId", "SESSION-001")
        );

        assertNotNull(request.requestId());
        assertEquals(request.requestId(), response.requestId());
        assertEquals(request.protocolVersion(), response.protocolVersion());
    }

    @Test
    void payloadCannotBeMutated() {
        ProtocolMessage message = ProtocolMessage.request(
                MessageType.SUBMIT,
                Map.of("sessionId", "SESSION-001")
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> message.payload().put("status", "SUBMITTED")
        );
    }

    @Test
    void serverPushGetsItsOwnRequestId() {
        ProtocolMessage request = ProtocolMessage.request(
                MessageType.SUBMIT,
                Map.of("sessionId", "SESSION-001")
        );
        ProtocolMessage push = ProtocolMessage.push(
                MessageType.AUTO_SUBMIT,
                PayloadCodec.toMap(new Payloads.AutoSubmit(
                        "SESSION-001",
                        "AUTO_SUBMITTED",
                        ProtocolTestFixtures.DEADLINE
                ))
        );

        assertNotNull(push.requestId());
        assertNotEquals(request.requestId(), push.requestId());
    }

    @Test
    void rejectsNonPushTypeFromPushFactory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolMessage.push(MessageType.ERROR, Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "Failure"
                ))
        );
    }

    @Test
    void retryingTheSameMessagePreservesIdentityAndAnswerSnapshot() {
        ProtocolMessage original = ProtocolMessage.request(
                MessageType.ANSWER_UPDATE,
                PayloadCodec.toMap(new Payloads.AnswerUpdate(
                        "SESSION-001", "Q1", 3, "Q1-A"
                ))
        );
        ProtocolMessage retry = original;

        assertEquals(original.requestId(), retry.requestId());
        assertEquals(original.payload().get("version"), retry.payload().get("version"));
        assertEquals(original.payload().get("selectedOptionId"), retry.payload().get("selectedOptionId"));
    }
}
