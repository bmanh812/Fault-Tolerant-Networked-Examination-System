package vn.edu.exam.common.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static ProtocolMessage request(MessageType type, Map<String, Object> payload) {
        return request(type, null, payload);
    }

    public static ProtocolMessage request(MessageType type, String sessionId, Map<String, Object> payload) {
        return new ProtocolMessage(
                type,
                1,
                UUID.randomUUID().toString(),
                sessionId,
                Instant.now().toEpochMilli(),
                payload
        );
    }

    public static ProtocolMessage response(
            MessageType type,
            ProtocolMessage request,
            String sessionId,
            Map<String, Object> payload
    ) {
        return new ProtocolMessage(
                type,
                request.version(),
                request.requestId(),
                sessionId,
                Instant.now().toEpochMilli(),
                payload
        );
    }
}

