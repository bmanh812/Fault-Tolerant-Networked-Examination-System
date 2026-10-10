package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.edu.exam.common.protocol.MessageType.ERROR;
import static vn.edu.exam.common.protocol.MessageType.EXAM_STATE;
import static vn.edu.exam.common.protocol.MessageType.JOIN_EXAM;

class ExamClientJoinTest {
    @Test
    void joinsExamAppliesInitialStateAndOnlyThenBecomesActive() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");
            ProtocolMessage request = transport.takeWritten();

            assertEquals(JOIN_EXAM, request.type());
            assertEquals("CAND-001", request.payload().get("candidateId"));
            assertEquals("EXAM-001", request.payload().get("examId"));
            assertEquals(ClientState.JOINING, client.state());
            assertNull(client.examState().sessionId());

            transport.receive(examStateResponse(request, "EXAM-001"));
            ClientExamState.Snapshot snapshot = joined.get(1, TimeUnit.SECONDS);

            assertEquals(ClientState.ACTIVE, client.state());
            assertEquals("SESSION-001", snapshot.sessionId());
            assertEquals(snapshot, client.examState().snapshot());
            assertEquals(3, snapshot.answers().get("Q1").confirmedVersion());
        }
    }

    @Test
    void responseWithDifferentRequestIdIsIgnoredAndJoinTimesOut() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 70)) {
            CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");
            transport.takeWritten();
            transport.receive(ProtocolMessage.request(
                    EXAM_STATE,
                    PayloadCodec.toMap(ClientExamStateTest.validExamState("EXAM-001"))
            ));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> joined.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(ClientState.CONNECTED, client.state());
            assertNull(client.examState().sessionId());
        }
    }

    @Test
    void rejectsExamStateForDifferentExamWithoutPartialApply() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 1_000)) {
            CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");
            ProtocolMessage request = transport.takeWritten();
            transport.receive(examStateResponse(request, "EXAM-OTHER"));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> joined.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(ClientState.CONNECTED, client.state());
            assertNull(client.examState().sessionId());
        }
    }

    @Test
    void mapsExpectedJoinErrorCodesToClearClientException() throws Exception {
        FakeTransport transport = new FakeTransport();
        List<ErrorCode> expectedCodes = List.of(
                ErrorCode.CANDIDATE_NOT_ALLOWED,
                ErrorCode.EXAM_NOT_STARTED,
                ErrorCode.EXAM_ENDED,
                ErrorCode.SERVER_BUSY
        );

        try (ExamClient client = connectedClient(transport, 1_000)) {
            for (ErrorCode code : expectedCodes) {
                CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");
                ProtocolMessage request = transport.takeWritten();
                transport.receive(ProtocolMessage.response(
                        ERROR,
                        request,
                        PayloadCodec.toMap(new Payloads.ErrorResponse(code, "Server detail", null))
                ));

                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> joined.get(1, TimeUnit.SECONDS)
                );
                ExamJoinException joinError = assertInstanceOf(ExamJoinException.class, failure.getCause());
                assertEquals(code, joinError.errorCode());
                assertEquals(ClientState.CONNECTED, client.state());
                assertNull(client.examState().sessionId());
            }
        }
    }

    @Test
    void requestTimeoutIsExposedAndDoesNotActivateClient() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = connectedClient(transport, 60)) {
            CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> joined.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(ClientState.CONNECTED, client.state());
            assertNull(client.examState().sessionId());
        }
    }

    private static ExamClient connectedClient(FakeTransport transport, int requestTimeoutMs) throws IOException {
        ExamClient client = new ExamClient(
                new ClientTransportConfig(500, 100, requestTimeoutMs, 65_536),
                (host, port, ignored) -> transport
        );
        client.connect("test-host", 9090);
        return client;
    }

    private static ProtocolMessage examStateResponse(ProtocolMessage request, String examId) {
        return ProtocolMessage.response(
                EXAM_STATE,
                request,
                PayloadCodec.toMap(ClientExamStateTest.validExamState(examId))
        );
    }

    private static final class FakeTransport implements ProtocolTransport {
        private static final Object EOF = new Object();

        private final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private final BlockingQueue<ProtocolMessage> written = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ProtocolMessage read() throws IOException {
            try {
                Object next = incoming.take();
                return next == EOF ? null : (ProtocolMessage) next;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Reader interrupted", error);
            }
        }

        @Override
        public void write(ProtocolMessage message) throws IOException {
            if (closed.get()) {
                throw new IOException("Transport closed");
            }
            written.add(message);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                incoming.offer(EOF);
            }
        }

        void receive(ProtocolMessage message) {
            incoming.add(message);
        }

        ProtocolMessage takeWritten() throws InterruptedException {
            return written.poll(1, TimeUnit.SECONDS);
        }
    }
}
