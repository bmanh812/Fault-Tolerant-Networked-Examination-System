package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_ACK;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_UPDATE;
import static vn.edu.exam.common.protocol.MessageType.ERROR;
import static vn.edu.exam.common.protocol.MessageType.EXAM_STATE;

class ExamClientAnswerUpdateTest {
    private static final long NOW = 1_790_800_000_000L;

    @Test
    void sendsFrozenSnapshotAndMarksAnswerSavedOnlyAfterMatchingAck() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            client.selectAnswer("Q2", "Q2-A");
            AnswerDraft.UpdateSnapshot snapshot = client.prepareAnswerUpdate("Q2");

            CompletableFuture<AnswerDraft> savedFuture = client.sendAnswerUpdate("Q2");
            ProtocolMessage request = transport.takeWritten();
            Payloads.AnswerUpdate payload = PayloadCodec.fromMap(request.payload(), Payloads.AnswerUpdate.class);

            assertEquals(ANSWER_UPDATE, request.type());
            assertEquals(snapshot.requestId(), request.requestId());
            assertEquals(snapshot.sessionId(), payload.sessionId());
            assertEquals(snapshot.questionId(), payload.questionId());
            assertEquals(snapshot.version(), payload.version());
            assertEquals(snapshot.selectedOptionId(), payload.selectedOptionId());
            assertFalse(savedFuture.isDone());
            assertFalse(client.examState().answer("Q2").isSaved());

            transport.receive(answerAck(request));
            AnswerDraft saved = savedFuture.get(1, TimeUnit.SECONDS);

            assertEquals(1, saved.confirmedVersion());
            assertTrue(saved.isSaved());
            assertFalse(client.answerUpdateInFlight("Q2"));
        }
    }

    @Test
    void serializesRapidChangesPerQuestionAndOldAckCannotSaveNewerSelection() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> versionOne = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage firstRequest = transport.takeWritten();

            CompletableFuture<AnswerDraft> versionTwo = client.updateAnswer("Q2", "Q2-B");
            assertNull(transport.pollWritten(75));
            assertEquals(2, client.examState().answer("Q2").localVersion());

            transport.receive(answerAck(firstRequest));
            versionOne.get(1, TimeUnit.SECONDS);
            ProtocolMessage secondRequest = transport.takeWritten();
            Payloads.AnswerUpdate secondPayload = PayloadCodec.fromMap(
                    secondRequest.payload(), Payloads.AnswerUpdate.class
            );

            AnswerDraft afterOldAck = client.examState().answer("Q2");
            assertEquals("Q2-B", afterOldAck.selectedOptionId());
            assertEquals(2, afterOldAck.localVersion());
            assertEquals(1, afterOldAck.confirmedVersion());
            assertFalse(afterOldAck.isSaved());
            assertEquals(2, secondPayload.version());
            assertEquals("Q2-B", secondPayload.selectedOptionId());
            assertNotEquals(firstRequest.requestId(), secondRequest.requestId());

            transport.receive(answerAck(secondRequest));
            AnswerDraft saved = versionTwo.get(1, TimeUnit.SECONDS);
            assertEquals(2, saved.confirmedVersion());
            assertEquals("Q2-B", saved.selectedOptionId());
            assertTrue(saved.isSaved());
        }
    }

    @Test
    void timeoutKeepsSnapshotAndRetryUsesSameIdentityWithoutIncreasingVersion() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 70)) {
            CompletableFuture<AnswerDraft> firstAttempt = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage original = transport.takeWritten();

            ExecutionException timeout = assertThrows(
                    ExecutionException.class,
                    () -> firstAttempt.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, timeout.getCause());
            assertFalse(client.answerUpdateInFlight("Q2"));

            AnswerDraft afterTimeout = client.examState().answer("Q2");
            assertEquals(1, afterTimeout.localVersion());
            assertEquals(0, afterTimeout.confirmedVersion());
            assertEquals(original.requestId(), afterTimeout.pendingRequestId());
            assertFalse(afterTimeout.isSaved());

            CompletableFuture<AnswerDraft> retryAttempt = client.sendAnswerUpdate("Q2");
            ProtocolMessage retry = transport.takeWritten();
            assertEquals(original.requestId(), retry.requestId());
            assertEquals(original.payload(), retry.payload());

            transport.receive(answerAck(retry));
            AnswerDraft saved = retryAttempt.get(1, TimeUnit.SECONDS);
            assertEquals(1, saved.localVersion());
            assertEquals(1, saved.confirmedVersion());
            assertTrue(saved.isSaved());
        }
    }

    @Test
    void versionConflictRetainsLocalStateAndExposesSyncNeededIssue() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> update = client.updateAnswer("Q2", "Q2-B");
            ProtocolMessage request = transport.takeWritten();
            transport.receive(ProtocolMessage.response(
                    ERROR,
                    request,
                    PayloadCodec.toMap(new Payloads.ErrorResponse(
                            ErrorCode.VERSION_CONFLICT,
                            "Server already has another version",
                            new Payloads.AnswerView("Q2", 1, "Q2-A")
                    ))
            ));

            ExecutionException failed = assertThrows(
                    ExecutionException.class,
                    () -> update.get(1, TimeUnit.SECONDS)
            );
            AnswerUpdateException conflict = assertInstanceOf(AnswerUpdateException.class, failed.getCause());
            assertEquals(ErrorCode.VERSION_CONFLICT, conflict.errorCode());
            assertTrue(conflict.requiresResync());

            AnswerDraft local = client.examState().answer("Q2");
            assertEquals("Q2-B", local.selectedOptionId());
            assertEquals(1, local.localVersion());
            assertEquals(0, local.confirmedVersion());
            assertEquals(request.requestId(), local.pendingRequestId());
            assertFalse(local.isSaved());

            AnswerSyncIssue issue = client.answerSyncIssue("Q2").orElseThrow();
            assertTrue(issue.requiresResync());
            assertEquals("Q2-A", issue.serverAnswer().selectedOptionId());

            CompletableFuture<AnswerDraft> blockedRetry = client.sendAnswerUpdate("Q2");
            ExecutionException blocked = assertThrows(
                    ExecutionException.class,
                    () -> blockedRetry.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(AnswerUpdateException.class, blocked.getCause());
            assertNull(transport.pollWritten(75));
        }
    }

    @Test
    void nonConflictErrorDoesNotCrashReaderOrDiscardRetrySnapshot() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> update = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage request = transport.takeWritten();
            transport.receive(ProtocolMessage.response(
                    ERROR,
                    request,
                    PayloadCodec.toMap(new Payloads.ErrorResponse(
                            ErrorCode.INVALID_SESSION, "Session rejected", null
                    ))
            ));

            ExecutionException failed = assertThrows(
                    ExecutionException.class,
                    () -> update.get(1, TimeUnit.SECONDS)
            );
            AnswerUpdateException answerError = assertInstanceOf(AnswerUpdateException.class, failed.getCause());
            assertEquals(ErrorCode.INVALID_SESSION, answerError.errorCode());
            assertTrue(client.readerThreadAlive());
            assertTrue(client.answerSyncIssue("Q2").isEmpty());
            assertEquals(request.requestId(), client.examState().answer("Q2").pendingRequestId());
        }
    }

    @Test
    void mismatchedAckIsRejectedWithoutConfirmingTheAnswer() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> update = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage request = transport.takeWritten();
            transport.receive(ProtocolMessage.response(
                    ANSWER_ACK,
                    request,
                    PayloadCodec.toMap(new Payloads.AnswerAck(
                            "SESSION-001", "Q2", 1, "Q2-B", NOW
                    ))
            ));

            ExecutionException failed = assertThrows(
                    ExecutionException.class,
                    () -> update.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            AnswerDraft local = client.examState().answer("Q2");
            assertEquals(0, local.confirmedVersion());
            assertEquals("Q2-A", local.selectedOptionId());
            assertFalse(local.isSaved());
            assertTrue(client.readerThreadAlive());
        }
    }

    private static ExamClient activeClient(FakeTransport transport, int timeoutMs) throws Exception {
        ExamClient client = new ExamClient(
                new ClientTransportConfig(500, 100, timeoutMs, 65_536),
                (host, port, ignored) -> transport
        );
        client.connect("test-host", 9090);
        CompletableFuture<ClientExamState.Snapshot> joined = client.joinExam("CAND-001", "EXAM-001");
        ProtocolMessage join = transport.takeWritten();
        transport.receive(ProtocolMessage.response(
                EXAM_STATE,
                join,
                PayloadCodec.toMap(ClientExamStateTest.validExamState("EXAM-001"))
        ));
        joined.get(1, TimeUnit.SECONDS);
        return client;
    }

    private static ProtocolMessage answerAck(ProtocolMessage request) {
        Payloads.AnswerUpdate update = PayloadCodec.fromMap(request.payload(), Payloads.AnswerUpdate.class);
        return ProtocolMessage.response(
                ANSWER_ACK,
                request,
                PayloadCodec.toMap(new Payloads.AnswerAck(
                        update.sessionId(),
                        update.questionId(),
                        update.version(),
                        update.selectedOptionId(),
                        NOW
                ))
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

        ProtocolMessage pollWritten(long timeoutMs) throws InterruptedException {
            return written.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
