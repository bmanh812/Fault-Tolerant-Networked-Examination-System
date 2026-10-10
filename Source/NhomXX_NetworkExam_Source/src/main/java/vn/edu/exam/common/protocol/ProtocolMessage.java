package vn.edu.exam.common.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ProtocolMessage(
        MessageType type,
        int protocolVersion,
        String requestId,
        Map<String, Object> payload
) {
    public static final int VERSION_1 = 1;

    public ProtocolMessage {
        Objects.requireNonNull(payload, "payload is required");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static ProtocolMessage request(MessageType type, Map<String, Object> payload) {
        return new ProtocolMessage(
                type,
                VERSION_1,
                UUID.randomUUID().toString(),
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
                payload
        );
    }

    public static ProtocolMessage push(MessageType type, Map<String, Object> payload) {
        if (type != MessageType.AUTO_SUBMIT) {
            throw new IllegalArgumentException("AUTO_SUBMIT is the only server-push message in protocol v1");
        }
        return request(type, payload);
    }
}

