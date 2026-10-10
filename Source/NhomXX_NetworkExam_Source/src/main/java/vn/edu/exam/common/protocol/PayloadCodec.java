package vn.edu.exam.common.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

public final class PayloadCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadCodec() {
    }

    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(Objects.requireNonNull(value, "value is required"), new TypeReference<>() {
        });
    }

    public static <T> T fromMap(Map<String, Object> payload, Class<T> targetType) {
        return MAPPER.convertValue(
                Objects.requireNonNull(payload, "payload is required"),
                Objects.requireNonNull(targetType, "targetType is required")
        );
    }
}
