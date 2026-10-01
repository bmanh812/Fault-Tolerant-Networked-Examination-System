package vn.edu.exam.common.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProtocolMessage(
        MessageType type,
        int version,
        String requestId,
        String sessionId,
        long sentAt,
        Map<String, Object> payload
) {
    public ProtocolMessage {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static ProtocolMessage request(MessageType type, Map<String, Object> payload) {
        return new ProtocolMessage(
                type,
                1,
                UUID.randomUUID().toString(),
                null,
                Instant.now().toEpochMilli(),
                payload
        );
    }
}

