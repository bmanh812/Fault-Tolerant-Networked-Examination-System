package vn.edu.exam.common.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class PayloadCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadCodec() {
    }

    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, new TypeReference<>() {
        });
    }

    public static <T> T fromMap(Map<String, Object> payload, Class<T> targetType) {
        return MAPPER.convertValue(payload, targetType);
    }
}
