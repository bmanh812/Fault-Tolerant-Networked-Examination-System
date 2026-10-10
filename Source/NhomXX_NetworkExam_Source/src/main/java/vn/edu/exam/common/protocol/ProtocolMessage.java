package vn.edu.exam.common.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ProtocolMessage(
        MessageType type,
        int protocolVersion,
        String requestId,
        long sentAt,
        Map<String, Object> payload
) {
    public static final int VERSION_1 = 1;

    public ProtocolMessage {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static ProtocolMessage request(MessageType type, Map<String, Object> payload) {
        return new ProtocolMessage(
                type,
                VERSION_1,
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                payload
        );
    }

    public static ProtocolMessage response(
            MessageType type,
            ProtocolMessage request,
            Map<String, Object> payload
    ) {
        return new ProtocolMessage(
                type,
                request.protocolVersion(),
                request.requestId(),
                Instant.now().toEpochMilli(),
                payload
        );
    }
}

