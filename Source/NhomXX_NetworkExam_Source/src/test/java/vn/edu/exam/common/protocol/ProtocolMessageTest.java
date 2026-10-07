package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolMessageTest {
    @Test
    void responseKeepsRequestCorrelationFields() {
        ProtocolMessage request = ProtocolMessage.request(
                MessageType.PING,
                Map.of("clientTime", 123L)
        );

        ProtocolMessage response = ProtocolMessage.response(
                MessageType.PONG,
                request,
                "session-001",
                Map.of("serverTime", 456L)
        );

        assertNotNull(request.requestId());
        assertEquals(request.requestId(), response.requestId());
        assertEquals(request.version(), response.version());
        assertEquals("session-001", response.sessionId());
    }

    @Test
    void payloadIsCopiedAndCannotBeMutated() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("value", "original");

        ProtocolMessage message = ProtocolMessage.request(MessageType.PING, source);
        source.put("value", "changed");

        assertEquals("original", message.payload().get("value"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> message.payload().put("another", "value")
        );
    }
}
