package vn.edu.exam.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolSerializationTest {
    @Test
    void roundTripsEveryMessageThroughJsonAndFraming() throws Exception {
        List<ProtocolMessage> expected = ProtocolTestFixtures.validMessages();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProtocolIO writer = new ProtocolIO(new ByteArrayInputStream(new byte[0]), output, 65_536);

        for (ProtocolMessage message : expected) {
            writer.write(message);
        }

        ProtocolIO reader = new ProtocolIO(
                new ByteArrayInputStream(output.toByteArray()),
                new ByteArrayOutputStream(),
                65_536
        );
        for (ProtocolMessage message : expected) {
            ProtocolMessage decoded = reader.read();
            assertEquals(message, decoded, message.type().name());
            ProtocolValidator.validate(decoded);
        }
    }

    @Test
    void roundTripsEveryPayloadRecord() {
        assertRoundTrip(new Payloads.JoinExamRequest("CAND-001", "EXAM-001"), Payloads.JoinExamRequest.class);
        assertRoundTrip(ProtocolTestFixtures.option(), Payloads.OptionView.class);
        assertRoundTrip(ProtocolTestFixtures.question(), Payloads.QuestionView.class);
        assertRoundTrip(ProtocolTestFixtures.answer(), Payloads.AnswerView.class);
        assertRoundTrip(new Payloads.ExamState(
                "SESSION-001", "EXAM-001", "Exam", "ACTIVE",
                ProtocolTestFixtures.DEADLINE, ProtocolTestFixtures.NOW,
                List.of(ProtocolTestFixtures.question()), Map.of("Q1", ProtocolTestFixtures.answer())
        ), Payloads.ExamState.class);
        assertRoundTrip(new Payloads.AnswerUpdate("SESSION-001", "Q1", 1, "Q1-A"), Payloads.AnswerUpdate.class);
        assertRoundTrip(new Payloads.AnswerAck("SESSION-001", "Q1", 1, "Q1-A", ProtocolTestFixtures.NOW), Payloads.AnswerAck.class);
        assertRoundTrip(new Payloads.Heartbeat("SESSION-001", ProtocolTestFixtures.NOW), Payloads.Heartbeat.class);
        assertRoundTrip(new Payloads.HeartbeatAck(ProtocolTestFixtures.NOW, ProtocolTestFixtures.DEADLINE), Payloads.HeartbeatAck.class);
        assertRoundTrip(new Payloads.ReconnectRequest("SESSION-001", "CAND-001", Map.of("Q1", 1)), Payloads.ReconnectRequest.class);
        assertRoundTrip(new Payloads.ResyncState(
                "SESSION-001", "ACTIVE", ProtocolTestFixtures.DEADLINE,
                ProtocolTestFixtures.NOW, Map.of("Q1", ProtocolTestFixtures.answer())
        ), Payloads.ResyncState.class);
        assertRoundTrip(new Payloads.SubmitRequest("SESSION-001"), Payloads.SubmitRequest.class);
        assertRoundTrip(new Payloads.SubmitAck("SESSION-001", "SUBMITTED", ProtocolTestFixtures.NOW), Payloads.SubmitAck.class);
        assertRoundTrip(new Payloads.AutoSubmit("SESSION-001", "AUTO_SUBMITTED", ProtocolTestFixtures.NOW), Payloads.AutoSubmit.class);
        assertRoundTrip(new Payloads.ErrorResponse(
                ErrorCode.VERSION_CONFLICT, "Version conflict", ProtocolTestFixtures.answer()
        ), Payloads.ErrorResponse.class);
    }

    @Test
    void rejectsUnknownEnvelopeFieldFromOldProtocol() throws Exception {
        String json = """
                {"type":"SUBMIT","protocolVersion":1,
                 "requestId":"50000000-0000-0000-0000-000000000001",
                 "sentAt":1790800000000,"payload":{"sessionId":"SESSION-001"}}
                """;
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        FrameCodec.writeFrame(framed, json, 65_536);
        ProtocolIO reader = new ProtocolIO(
                new ByteArrayInputStream(framed.toByteArray()),
                new ByteArrayOutputStream(),
                65_536
        );

        assertThrows(IOException.class, reader::read);
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) {
        assertEquals(value, PayloadCodec.fromMap(PayloadCodec.toMap(value), type));
    }
}
