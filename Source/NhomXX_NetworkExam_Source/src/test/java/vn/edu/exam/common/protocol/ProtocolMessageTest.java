package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
