package vn.edu.exam.common.protocol;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProtocolValidator {
    private static final Set<MessageType> CLIENT_MESSAGE_TYPES = Set.of(
            MessageType.JOIN_EXAM,
            MessageType.ANSWER_UPDATE,
            MessageType.HEARTBEAT,
            MessageType.RECONNECT,
            MessageType.SUBMIT
    );
    private static final Set<MessageType> SERVER_MESSAGE_TYPES = Set.of(
            MessageType.EXAM_STATE,
            MessageType.ANSWER_ACK,
            MessageType.HEARTBEAT_ACK,
            MessageType.RESYNC_STATE,
            MessageType.SUBMIT_ACK,
            MessageType.AUTO_SUBMIT,
            MessageType.ERROR
    );
    private static final Set<String> SESSION_STATUSES = Set.of(
            "NEW", "ACTIVE", "SUBMITTED", "AUTO_SUBMITTED"
    );

    private ProtocolValidator() {
    }

    public static void validate(ProtocolMessage message) {
        validateEnvelopeFields(message);
        Map<String, Object> payload = message.payload();

        switch (message.type()) {
            case JOIN_EXAM -> validateJoinExam(payload);
            case EXAM_STATE -> validateExamState(payload);
            case ANSWER_UPDATE -> validateAnswerUpdate(payload);
            case ANSWER_ACK -> validateAnswerAck(payload);
            case HEARTBEAT -> validateHeartbeat(payload);
            case HEARTBEAT_ACK -> validateHeartbeatAck(payload);
            case RECONNECT -> validateReconnect(payload);
            case RESYNC_STATE -> validateResyncState(payload);
            case SUBMIT -> validateSubmit(payload);
            case SUBMIT_ACK -> validateSubmitAck(payload);
            case AUTO_SUBMIT -> validateAutoSubmit(payload);
            case ERROR -> validateError(payload);
        }
    }

    public static void validateClientMessage(ProtocolMessage message) {
        validate(message);
        if (!CLIENT_MESSAGE_TYPES.contains(message.type())) {
            fail(message.type() + " is not a Client-to-Server message");
        }
    }

    public static void validateServerMessage(ProtocolMessage message) {
        validate(message);
        if (!SERVER_MESSAGE_TYPES.contains(message.type())) {
            fail(message.type() + " is not a Server-to-Client message");
        }
    }

    public static void validateResponseTo(ProtocolMessage request, ProtocolMessage response) {
        validateClientMessage(request);
        validateServerMessage(response);
        MessageType expectedType = switch (request.type()) {
            case JOIN_EXAM -> MessageType.EXAM_STATE;
            case ANSWER_UPDATE -> MessageType.ANSWER_ACK;
            case HEARTBEAT -> MessageType.HEARTBEAT_ACK;
            case RECONNECT -> MessageType.RESYNC_STATE;
            case SUBMIT -> MessageType.SUBMIT_ACK;
            default -> throw new IllegalStateException("Unexpected client message type: " + request.type());
        };
        if (response.type() != expectedType && response.type() != MessageType.ERROR) {
            fail(response.type() + " is not a response to " + request.type());
        }
        if (!request.requestId().equals(response.requestId())) {
            fail("Direct response must use the request requestId");
        }
    }

    /**
     * Kept as a compatibility entry point for the current transport skeleton.
     * Protocol v1 validation now includes the message-specific payload contract.
     */
    public static void validateEnvelope(ProtocolMessage message) {
        validate(message);
    }

    private static void validateEnvelopeFields(ProtocolMessage message) {
        if (message == null) {
            fail("message is required");
        }
        if (message.type() == null) {
            fail("type is required");
        }
        if (message.protocolVersion() != ProtocolMessage.VERSION_1) {
            fail("Unsupported protocolVersion: " + message.protocolVersion());
        }
        String requestId = requiredString(message.requestId(), "requestId");
        try {
            if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
                fail("requestId must be a canonical UUID");
            }
        } catch (IllegalArgumentException error) {
            fail("requestId must be a UUID");
        }
        if (message.payload() == null) {
            fail("payload is required");
        }
    }

    private static void validateJoinExam(Map<String, Object> payload) {
        exactFields(payload, required("candidateId", "examId"), optional());
        requiredString(payload, "candidateId");
        requiredString(payload, "examId");
    }

    private static void validateExamState(Map<String, Object> payload) {
        exactFields(
                payload,
                required("sessionId", "examId", "title", "status", "deadline", "serverTime", "questions", "answers"),
                optional()
        );
        requiredString(payload, "sessionId");
        requiredString(payload, "examId");
        requiredString(payload, "title");
        requireStatus(payload, "status", Set.of("ACTIVE"));
        positiveLong(payload, "deadline");
        positiveLong(payload, "serverTime");

        List<?> questions = requiredList(payload, "questions");
        if (questions.isEmpty()) {
            fail("questions must not be empty");
        }
        for (int index = 0; index < questions.size(); index++) {
            validateQuestion(requiredMapValue(questions.get(index), "questions[" + index + "]"));
        }
        validateAnswers(requiredMap(payload, "answers"));
    }

    private static void validateQuestion(Map<String, Object> question) {
        exactFields(question, required("questionId", "position", "content", "options"), optional());
        requiredString(question, "questionId");
        positiveInt(question, "position");
        requiredString(question, "content");
        List<?> options = requiredList(question, "options");
        if (options.isEmpty()) {
            fail("options must not be empty");
        }
        for (int index = 0; index < options.size(); index++) {
            Map<String, Object> option = requiredMapValue(options.get(index), "options[" + index + "]");
            exactFields(option, required("optionId", "label", "content"), optional());
            requiredString(option, "optionId");
            requiredString(option, "label");
            requiredString(option, "content");
        }
    }

    private static void validateAnswerUpdate(Map<String, Object> payload) {
        exactFields(payload, required("sessionId", "questionId", "version", "selectedOptionId"), optional());
        validateAnswerFields(payload, "version");
    }

    private static void validateAnswerAck(Map<String, Object> payload) {
        exactFields(
                payload,
                required("sessionId", "questionId", "confirmedVersion", "selectedOptionId", "serverTime"),
                optional()
        );
        validateAnswerFields(payload, "confirmedVersion");
        positiveLong(payload, "serverTime");
    }

    private static void validateAnswerFields(Map<String, Object> payload, String versionField) {
        requiredString(payload, "sessionId");
        requiredString(payload, "questionId");
        positiveInt(payload, versionField);
        requiredString(payload, "selectedOptionId");
    }

    private static void validateHeartbeat(Map<String, Object> payload) {
        exactFields(payload, required("sessionId", "clientTime"), optional());
        requiredString(payload, "sessionId");
        positiveLong(payload, "clientTime");
    }

    private static void validateHeartbeatAck(Map<String, Object> payload) {
        exactFields(payload, required("serverTime", "deadline"), optional());
        positiveLong(payload, "serverTime");
        positiveLong(payload, "deadline");
    }

    private static void validateReconnect(Map<String, Object> payload) {
        exactFields(payload, required("sessionId", "candidateId", "knownVersions"), optional());
        requiredString(payload, "sessionId");
        requiredString(payload, "candidateId");
        Map<String, Object> versions = requiredMap(payload, "knownVersions");
        for (Map.Entry<String, Object> entry : versions.entrySet()) {
            requiredString(entry.getKey(), "knownVersions key");
            positiveInt(entry.getValue(), "knownVersions." + entry.getKey());
        }
    }

    private static void validateResyncState(Map<String, Object> payload) {
        exactFields(
                payload,
                required("sessionId", "status", "deadline", "serverTime", "answers"),
                optional()
        );
        requiredString(payload, "sessionId");
        requireStatus(payload, "status", Set.of("ACTIVE", "SUBMITTED", "AUTO_SUBMITTED"));
        positiveLong(payload, "deadline");
        positiveLong(payload, "serverTime");
        validateAnswers(requiredMap(payload, "answers"));
    }

    private static void validateAnswers(Map<String, Object> answers) {
        for (Map.Entry<String, Object> entry : answers.entrySet()) {
            String questionId = requiredString(entry.getKey(), "answers key");
            Map<String, Object> answer = requiredMapValue(entry.getValue(), "answers." + questionId);
            exactFields(answer, required("questionId", "version", "selectedOptionId"), optional());
            if (!questionId.equals(requiredString(answer, "questionId"))) {
                fail("answers key must match answer.questionId");
            }
            positiveInt(answer, "version");
            requiredString(answer, "selectedOptionId");
        }
    }

    private static void validateSubmit(Map<String, Object> payload) {
        exactFields(payload, required("sessionId"), optional());
        requiredString(payload, "sessionId");
    }

    private static void validateSubmitAck(Map<String, Object> payload) {
        exactFields(payload, required("sessionId", "status", "submittedAt"), optional());
        requiredString(payload, "sessionId");
        requireStatus(payload, "status", Set.of("SUBMITTED"));
        positiveLong(payload, "submittedAt");
    }

    private static void validateAutoSubmit(Map<String, Object> payload) {
        exactFields(payload, required("sessionId", "status", "submittedAt"), optional());
        requiredString(payload, "sessionId");
        requireStatus(payload, "status", Set.of("AUTO_SUBMITTED"));
        positiveLong(payload, "submittedAt");
    }

    private static void validateError(Map<String, Object> payload) {
        exactFields(payload, required("code", "message"), optional("currentAnswer"));
        String code = requiredString(payload, "code");
        ErrorCode errorCode;
        try {
            errorCode = ErrorCode.valueOf(code);
        } catch (IllegalArgumentException error) {
            fail("Unknown error code: " + code);
            return;
        }
        requiredString(payload, "message");
        Object currentAnswer = payload.get("currentAnswer");
        if (errorCode == ErrorCode.VERSION_CONFLICT && currentAnswer == null) {
            fail("currentAnswer is required for VERSION_CONFLICT");
        }
        if (errorCode != ErrorCode.VERSION_CONFLICT && currentAnswer != null) {
            fail("currentAnswer is only allowed for VERSION_CONFLICT");
        }
        if (currentAnswer != null) {
            Map<String, Object> answer = requiredMapValue(currentAnswer, "currentAnswer");
            exactFields(answer, required("questionId", "version", "selectedOptionId"), optional());
            requiredString(answer, "questionId");
            positiveInt(answer, "version");
            requiredString(answer, "selectedOptionId");
        }
    }

    private static void requireStatus(Map<String, Object> payload, String field, Set<String> allowed) {
        String status = requiredString(payload, field);
        if (!SESSION_STATUSES.contains(status) || !allowed.contains(status)) {
            fail(field + " must be one of " + allowed);
        }
    }

    private static void exactFields(Map<String, Object> payload, Set<String> required, Set<String> optional) {
        for (String field : required) {
            if (!payload.containsKey(field)) {
                fail(field + " is required");
            }
        }
        for (String field : payload.keySet()) {
            if (!required.contains(field) && !optional.contains(field)) {
                fail("Unknown payload field: " + field);
            }
        }
    }

    private static Set<String> required(String... fields) {
        return Set.of(fields);
    }

    private static Set<String> optional(String... fields) {
        return Set.of(fields);
    }

    private static String requiredString(Map<String, Object> payload, String field) {
        return requiredString(payload.get(field), field);
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            fail(field + " must be a non-blank string");
        }
        return (String) value;
    }

    private static int positiveInt(Map<String, Object> payload, String field) {
        return positiveInt(payload.get(field), field);
    }

    private static int positiveInt(Object value, String field) {
        if (!(value instanceof Number)) {
            fail(field + " must be an integer");
        }
        Number number = (Number) value;
        double doubleValue = number.doubleValue();
        long longValue = number.longValue();
        if (!Double.isFinite(doubleValue) || doubleValue != longValue || longValue < 1 || longValue > Integer.MAX_VALUE) {
            fail(field + " must be an integer greater than or equal to 1");
        }
        return (int) longValue;
    }

    private static long positiveLong(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof Number)) {
            fail(field + " must be an integer");
        }
        Number number = (Number) value;
        double doubleValue = number.doubleValue();
        long longValue = number.longValue();
        if (!Double.isFinite(doubleValue) || doubleValue != longValue || longValue < 1) {
            fail(field + " must be a positive integer");
        }
        return longValue;
    }

    private static List<?> requiredList(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof List<?>)) {
            fail(field + " must be an array");
        }
        return (List<?>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Map<String, Object> payload, String field) {
        return requiredMapValue(payload.get(field), field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMapValue(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            fail(field + " must be an object");
        }
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (!(key instanceof String)) {
                fail(field + " keys must be strings");
            }
        }
        return (Map<String, Object>) value;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
