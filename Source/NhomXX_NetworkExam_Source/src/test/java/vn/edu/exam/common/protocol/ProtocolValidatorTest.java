package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolValidatorTest {
    @Test
    void acceptsEveryProtocolV1MessageType() {
        for (ProtocolMessage message : ProtocolTestFixtures.validMessages()) {
            assertDoesNotThrow(() -> ProtocolValidator.validate(message), message.type().name());
        }
    }

    @Test
    void rejectsMissingRequiredField() {
        ProtocolMessage message = ProtocolMessage.request(
                MessageType.ANSWER_UPDATE,
                Map.of(
                        "sessionId", "SESSION-001",
                        "questionId", "Q1",
                        "selectedOptionId", "Q1-A"
                )
        );

        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(message));
    }

    @Test
    void rejectsNonCanonicalRequestId() {
        ProtocolMessage message = new ProtocolMessage(
                MessageType.SUBMIT,
                ProtocolMessage.VERSION_1,
                "1-1-1-1-1",
                Map.of("sessionId", "SESSION-001")
        );

        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(message));
    }

    @Test
    void rejectsLegacySequenceFields() {
        Map<String, Object> payload = new LinkedHashMap<>(PayloadCodec.toMap(
                new Payloads.AnswerUpdate("SESSION-001", "Q1", 1, "Q1-A")
        ));
        payload.put("seq", 10);

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolValidator.validate(ProtocolMessage.request(MessageType.ANSWER_UPDATE, payload))
        );
    }

    @Test
    void rejectsVersionLowerThanOne() {
        ProtocolMessage message = ProtocolMessage.request(
                MessageType.ANSWER_UPDATE,
                Map.of(
                        "sessionId", "SESSION-001",
                        "questionId", "Q1",
                        "version", 0,
                        "selectedOptionId", "Q1-A"
                )
        );

        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(message));
    }

    @Test
    void rejectsCorrectAnswerLeakInExamState() {
        Map<String, Object> option = new LinkedHashMap<>(PayloadCodec.toMap(ProtocolTestFixtures.option()));
        option.put("correctOptionId", "Q1-A");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("questionId", "Q1");
        question.put("position", 1);
        question.put("content", "Question");
        question.put("options", List.of(option));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", "SESSION-001");
        payload.put("examId", "EXAM-001");
        payload.put("title", "Exam");
        payload.put("status", "ACTIVE");
        payload.put("deadline", ProtocolTestFixtures.DEADLINE);
        payload.put("serverTime", ProtocolTestFixtures.NOW);
        payload.put("questions", List.of(question));
        payload.put("answers", Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolValidator.validate(ProtocolMessage.request(MessageType.EXAM_STATE, payload))
        );
    }

    @Test
    void rejectsUnknownErrorCode() {
        ProtocolMessage message = ProtocolMessage.request(
                MessageType.ERROR,
                Map.of("code", "OLD_ERROR", "message", "Unknown")
        );

        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(message));
    }

    @Test
    void acceptsErrorWithoutOptionalCurrentAnswer() {
        ProtocolMessage message = ProtocolMessage.request(
                MessageType.ERROR,
                Map.of("code", "INVALID_REQUEST", "message", "Invalid request")
        );

        assertDoesNotThrow(() -> ProtocolValidator.validate(message));
    }

    @Test
    void rejectsAnswerMapWhoseKeyDoesNotMatchQuestionId() {
        Map<String, Object> payload = new LinkedHashMap<>(PayloadCodec.toMap(new Payloads.ResyncState(
                "SESSION-001",
                "ACTIVE",
                ProtocolTestFixtures.DEADLINE,
                ProtocolTestFixtures.NOW,
                Map.of("Q2", ProtocolTestFixtures.answer())
        )));

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolValidator.validate(ProtocolMessage.request(MessageType.RESYNC_STATE, payload))
        );
    }

    @Test
    void enforcesMessageDirection() {
        ProtocolMessage clientMessage = ProtocolTestFixtures.validMessages().get(0);
        ProtocolMessage serverMessage = ProtocolTestFixtures.validMessages().get(1);

        assertDoesNotThrow(() -> ProtocolValidator.validateClientMessage(clientMessage));
        assertDoesNotThrow(() -> ProtocolValidator.validateServerMessage(serverMessage));
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validateServerMessage(clientMessage));
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validateClientMessage(serverMessage));
    }

    @Test
    void validatesDirectResponseTypeAndRequestId() {
        ProtocolMessage request = ProtocolTestFixtures.message(
                MessageType.ANSWER_UPDATE,
                new Payloads.AnswerUpdate("SESSION-001", "Q1", 1, "Q1-A")
        );
        ProtocolMessage response = ProtocolMessage.response(
                MessageType.ANSWER_ACK,
                request,
                PayloadCodec.toMap(new Payloads.AnswerAck(
                        "SESSION-001", "Q1", 1, "Q1-A", ProtocolTestFixtures.NOW
                ))
        );
        ProtocolMessage wrongRequestId = ProtocolTestFixtures.message(
                MessageType.ANSWER_ACK,
                new Payloads.AnswerAck("SESSION-001", "Q1", 1, "Q1-A", ProtocolTestFixtures.NOW)
        );

        assertDoesNotThrow(() -> ProtocolValidator.validateResponseTo(request, response));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolValidator.validateResponseTo(request, wrongRequestId)
        );
    }

    @Test
    void requiresCurrentAnswerOnlyForVersionConflict() {
        ProtocolMessage conflictWithoutState = ProtocolMessage.request(
                MessageType.ERROR,
                Map.of("code", "VERSION_CONFLICT", "message", "Conflict")
        );
        ProtocolMessage unrelatedErrorWithState = ProtocolMessage.request(
                MessageType.ERROR,
                PayloadCodec.toMap(new Payloads.ErrorResponse(
                        ErrorCode.INVALID_REQUEST,
                        "Invalid",
                        ProtocolTestFixtures.answer()
                ))
        );

        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(conflictWithoutState));
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidator.validate(unrelatedErrorWithState));
    }
}
