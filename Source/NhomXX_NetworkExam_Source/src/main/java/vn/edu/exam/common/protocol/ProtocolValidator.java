package vn.edu.exam.common.protocol;

import java.util.Map;
import java.util.UUID;

public final class ProtocolValidator {
    private ProtocolValidator() {
    }

    public static void validateEnvelope(ProtocolMessage message) {
        if (message == null || message.type() == null) {
            throw new IllegalArgumentException("Message type is required");
        }
        if (message.protocolVersion() != ProtocolMessage.VERSION_1) {
            throw new IllegalArgumentException("Unsupported protocol version: " + message.protocolVersion());
        }
        UUID.fromString(message.requestId());
        if (message.sentAt() <= 0) {
            throw new IllegalArgumentException("sentAt must be positive");
        }
        Map<String, Object> payload = message.payload();
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }
}
