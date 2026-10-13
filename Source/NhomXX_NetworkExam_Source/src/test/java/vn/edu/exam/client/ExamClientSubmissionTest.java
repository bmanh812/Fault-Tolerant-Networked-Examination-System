package vn.edu.exam.client;

import org.junit.jupiter.api.Test;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.common.protocol.ErrorCode;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_ACK;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_UPDATE;
import static vn.edu.exam.common.protocol.MessageType.AUTO_SUBMIT;
import static vn.edu.exam.common.protocol.MessageType.EXAM_STATE;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT;
import static vn.edu.exam.common.protocol.MessageType.SUBMIT_ACK;
import static vn.edu.exam.common.protocol.MessageType.ERROR;

class ExamClientSubmissionTest {
    private static final long SUBMITTED_AT = 1_790_800_100_000L;

    @Test
    void submitsImmediatelyWhenAllAnswersAreConfirmed() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();

            assertEquals(SUBMIT, request.type());
            assertEquals("SESSION-001", request.payload().get("sessionId"));
            assertEquals(ClientState.SUBMITTING, client.state());

            transport.receive(submitAck(request, "SESSION-001"));
            ExamSubmission result = submitted.get(1, TimeUnit.SECONDS);

            assertEquals(ClientState.SUBMITTED, result.state());
            assertEquals(ClientState.SUBMITTED, client.state());
            assertEquals(SUBMITTED_AT, result.submittedAt());
            assertEquals("SUBMITTED", client.examState().sessionStatus());
            assertTrue(client.submitRequestId().isEmpty());
        }
    }

    @Test
    void waitsForPendingAnswerAckBeforeSendingSubmit() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> answer = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage answerRequest = transport.takeWritten();

            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            assertEquals(ClientState.SUBMITTING, client.state());
            assertNull(transport.pollWritten(75));

            transport.receive(answerAck(answerRequest));
            answer.get(1, TimeUnit.SECONDS);
            ProtocolMessage submitRequest = transport.takeWritten();
            assertEquals(SUBMIT, submitRequest.type());

            transport.receive(submitAck(submitRequest, "SESSION-001"));
            assertEquals(ClientState.SUBMITTED, submitted.get(1, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void unsynchronizedAnswerPreventsSubmitAndRemainsRetryable() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 60)) {
            client.selectAnswer("Q2", "Q2-A");
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage answerRequest = transport.takeWritten();
            assertEquals(ANSWER_UPDATE, answerRequest.type());

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> submitted.get(1, TimeUnit.SECONDS)
            );
            SubmitException submitError = assertInstanceOf(SubmitException.class, failure.getCause());
            assertTrue(submitError.answerSyncFailure());
            assertEquals(ClientState.SUBMITTING, client.state());
            assertTrue(client.submitRetryAvailable());
            assertNull(transport.pollWritten(75));
        }
    }

    @Test
    void wrongRequestIdCannotFinalizeSubmit() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 60)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            ProtocolMessage otherRequest = ProtocolMessage.request(
                    SUBMIT,
                    PayloadCodec.toMap(new Payloads.SubmitRequest("SESSION-001"))
            );

            transport.receive(submitAck(otherRequest, "SESSION-001"));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> submitted.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals(ClientState.SUBMITTING, client.state());
            assertEquals(request.requestId(), client.submitRequestId().orElseThrow());
        }
    }

    @Test
    void wrongSessionAckCannotFinalizeSubmit() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();

            transport.receive(submitAck(request, "SESSION-OTHER"));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> submitted.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(ClientState.SUBMITTING, client.state());
            assertTrue(client.submission().isEmpty());
        }
    }

    @Test
    void timeoutRetryReusesSameLogicalRequestId() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 60)) {
            CompletableFuture<ExamSubmission> first = client.submitExam();
            ProtocolMessage original = transport.takeWritten();
            ExecutionException timeout = assertThrows(
                    ExecutionException.class,
                    () -> first.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(TimeoutException.class, timeout.getCause());
            assertTrue(client.submitRetryAvailable());

            CompletableFuture<ExamSubmission> retried = client.submitExam();
            ProtocolMessage retry = transport.takeWritten();
            assertEquals(original.requestId(), retry.requestId());
            assertEquals(original.payload(), retry.payload());

            transport.receive(submitAck(retry, "SESSION-001"));
            assertEquals(ClientState.SUBMITTED, retried.get(1, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void duplicateSubmitAckIsIdempotent() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            ProtocolMessage acknowledgement = submitAck(request, "SESSION-001");

            transport.receiveAndAwait(acknowledgement);
            ExamSubmission first = submitted.get(1, TimeUnit.SECONDS);
            transport.receive(acknowledgement);

            assertEquals(first, client.submission().orElseThrow());
            assertEquals(ClientState.SUBMITTED, client.state());
            assertTrue(client.readerThreadAlive());
        }
    }

    @Test
    void submittedClientRejectsFurtherAnswerChanges() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            submitted.get(1, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class, () -> client.selectAnswer("Q2", "Q2-A"));
            assertThrows(IllegalStateException.class, () -> client.updateAnswer("Q2", "Q2-A"));
            assertThrows(IllegalStateException.class, () -> client.examState().selectAnswer("Q2", "Q2-A"));
            assertThrows(IllegalStateException.class, () -> client.sendAnswerUpdate("Q2"));
            assertThrows(IllegalStateException.class, () -> client.sendRequest(ProtocolMessage.request(
                    ANSWER_UPDATE, PayloadCodec.toMap(new Payloads.AnswerUpdate("SESSION-001", "Q2", 1, "Q2-A")))));
            assertNull(transport.pollWritten(75));
        }
    }

    @Test
    void autoSubmitFromActiveIsAuthoritativeAndNotifiesListener() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> notification = new CompletableFuture<>();
            client.setSubmissionHandler(notification::complete);

            transport.receive(autoSubmit("SESSION-001"));
            ExamSubmission result = notification.get(1, TimeUnit.SECONDS);

            assertEquals(ClientState.AUTO_SUBMITTED, result.state());
            assertEquals(ClientState.AUTO_SUBMITTED, client.state());
            assertThrows(IllegalStateException.class, () -> client.selectAnswer("Q2", "Q2-A"));
        }
    }

    @Test
    void autoSubmitWhileSubmittingCompletesAttemptAndLateAckCannotOverrideIt() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();

            transport.receive(autoSubmit("SESSION-001"));
            ExamSubmission auto = submitted.get(1, TimeUnit.SECONDS);
            assertEquals(ClientState.AUTO_SUBMITTED, auto.state());

            transport.receive(submitAck(request, "SESSION-001"));
            assertEquals(ClientState.AUTO_SUBMITTED, client.state());
            assertTrue(client.submission().orElseThrow().autoSubmitted());
        }
    }

    @Test
    void duplicateAutoSubmitIsIdempotent() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            ProtocolMessage event = autoSubmit("SESSION-001");
            transport.receiveAndAwait(event);
            ExamSubmission first = client.submission().orElseThrow();

            transport.receiveAndAwait(event);

            assertEquals(first, client.submission().orElseThrow());
            assertEquals(ClientState.AUTO_SUBMITTED, client.state());
            assertTrue(client.readerThreadAlive());
        }
    }

    @Test
    void autoSubmitForAnotherSessionIsIgnoredSafely() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            transport.receiveAndAwait(autoSubmit("SESSION-OTHER"));

            assertEquals(ClientState.ACTIVE, client.state());
            assertTrue(client.submission().isEmpty());
            assertTrue(client.readerThreadAlive());
        }
    }

    @Test
    void autoSubmitStopsNewerAnswerFromBeingSentAfterOlderAck() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<AnswerDraft> versionOne = client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage firstRequest = transport.takeWritten();
            CompletableFuture<AnswerDraft> versionTwo = client.updateAnswer("Q2", "Q2-B");

            transport.receiveAndAwait(autoSubmit("SESSION-001"));
            transport.receive(answerAck(firstRequest));
            versionOne.get(1, TimeUnit.SECONDS);

            ExecutionException blocked = assertThrows(
                    ExecutionException.class,
                    () -> versionTwo.get(1, TimeUnit.SECONDS)
            );
            assertInstanceOf(IllegalStateException.class, blocked.getCause());
            assertNull(transport.pollWritten(75));
        }
    }

    @Test
    void freezeIsLocalOnlyAndRejectsNewVersionsImmediately() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            client.selectAnswer("Q2", "Q2-A");
            client.beginSubmission();
            String identity = client.submitRequestId().orElseThrow();
            client.beginSubmission();
            assertEquals(identity, client.submitRequestId().orElseThrow());
            assertEquals(ClientState.SUBMITTING, client.state());
            assertFalse(client.submitRequestSent());
            assertThrows(IllegalStateException.class, () -> client.selectAnswer("Q2", "Q2-B"));
            assertEquals(1, client.examState().answer("Q2").localVersion());
            assertNull(transport.written.poll());
        }
    }

    @Test
    void submitFlushesNewestVersionAfterOlderInflightAckWithoutCreatingNextVersion() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            client.updateAnswer("Q2", "Q2-A");
            ProtocolMessage v1 = transport.takeWritten();
            client.updateAnswer("Q2", "Q2-B");
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            assertThrows(IllegalStateException.class, () -> client.updateAnswer("Q2", "Q2-A"));
            assertEquals(2, client.examState().answer("Q2").localVersion());
            transport.receiveAndAwait(answerAck(v1));
            ProtocolMessage v2 = transport.takeWritten();
            assertEquals(ANSWER_UPDATE, v2.type());
            assertEquals(2, v2.payload().get("version"));
            assertEquals("Q2-B", v2.payload().get("selectedOptionId"));
            assertNull(transport.written.poll());
            transport.receiveAndAwait(answerAck(v2));
            ProtocolMessage request = transport.takeWritten();
            assertEquals(SUBMIT, request.type());
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            assertEquals(ClientState.SUBMITTED, submitted.get(1, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void failedFlushRetryReusesAnswerSnapshotAndSubmitIdentity() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 100)) {
            client.selectAnswer("Q2", "Q2-A");
            CompletableFuture<ExamSubmission> first = client.submitExam();
            String submitId = client.submitRequestId().orElseThrow();
            ProtocolMessage original = transport.takeWritten();
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS));
            assertFalse(client.submitRequestSent());
            CompletableFuture<ExamSubmission> retry = client.submitExam();
            ProtocolMessage repeated = transport.takeWritten();
            assertEquals(original, repeated);
            assertEquals(1, client.examState().answer("Q2").localVersion());
            transport.receiveAndAwait(answerAck(repeated));
            ProtocolMessage submit = transport.takeWritten();
            assertEquals(submitId, submit.requestId());
            transport.receiveAndAwait(submitAck(submit, "SESSION-001"));
            retry.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentSubmitCallsShareOneAttempt() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> first = client.submitExam();
            CompletableFuture<ExamSubmission> second = client.submitExam();
            assertTrue(first == second);
            ProtocolMessage request = transport.takeWritten();
            assertNull(transport.written.poll());
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            assertEquals(first.get(), second.get());
        }
    }

    @Test
    void invalidStatusOrResponseTypeNeverFinalizesSubmit() throws Exception {
        for (boolean wrongType : new boolean[]{false, true}) {
            FakeTransport transport = new FakeTransport();
            try (ExamClient client = activeClient(transport, 1_000)) {
                CompletableFuture<ExamSubmission> submitted = client.submitExam();
                ProtocolMessage request = transport.takeWritten();
                ProtocolMessage invalid = wrongType
                        ? ProtocolMessage.response(ANSWER_ACK, request, PayloadCodec.toMap(
                                new Payloads.AnswerAck("SESSION-001", "Q2", 1, "Q2-A", SUBMITTED_AT)))
                        : ProtocolMessage.response(SUBMIT_ACK, request, PayloadCodec.toMap(
                                new Payloads.SubmitAck("SESSION-001", "ACTIVE", SUBMITTED_AT)));
                transport.receiveAndAwait(invalid);
                assertThrows(ExecutionException.class, () -> submitted.get(1, TimeUnit.SECONDS));
                assertEquals(ClientState.SUBMITTING, client.state());
                assertTrue(client.submission().isEmpty());
            }
        }
    }

    @Test
    void serverBusyIsExplicitAndRetryUsesSameRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> first = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            transport.receiveAndAwait(ProtocolMessage.response(ERROR, request, PayloadCodec.toMap(
                    new Payloads.ErrorResponse(ErrorCode.SERVER_BUSY, "Busy", null))));
            SubmitException failure = assertInstanceOf(SubmitException.class,
                    assertThrows(ExecutionException.class, () -> first.get()).getCause());
            assertEquals(ErrorCode.SERVER_BUSY, failure.errorCode());
            CompletableFuture<ExamSubmission> retry = client.submitExam();
            assertEquals(request, transport.takeWritten());
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            retry.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void autoSubmitDuringAnswerFlushCompletesSubmitWithoutSendingSubmitRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            client.selectAnswer("Q2", "Q2-A");
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage answer = transport.takeWritten();
            transport.receiveAndAwait(autoSubmit("SESSION-001"));
            assertTrue(submitted.get(1, TimeUnit.SECONDS).autoSubmitted());
            transport.receiveAndAwait(answerAck(answer));
            assertNull(transport.written.poll());
            assertEquals(0, client.pendingRequestCount());
            assertEquals("AUTO_SUBMITTED", client.examState().sessionStatus());
        }
    }

    @Test
    void autoSubmitClearsManualPendingEvenIfAckNeverArrives() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            transport.takeWritten();
            transport.receiveAndAwait(autoSubmit("SESSION-001"));
            assertTrue(submitted.get(1, TimeUnit.SECONDS).autoSubmitted());
            assertEquals(0, client.pendingRequestCount());
            assertFalse(client.submitRetryAvailable());
            assertTrue(client.submitRequestId().isEmpty());
        }
    }

    @Test
    void manualAckBeforeAutoSubmitKeepsFirstFinalStateAndNotifiesOnce() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
            client.setSubmissionHandler(ignored -> notifications.incrementAndGet());
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            ExamSubmission manual = submitted.get(1, TimeUnit.SECONDS);
            transport.receiveAndAwait(autoSubmit("SESSION-001"));
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            assertEquals(manual, client.submission().orElseThrow());
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void eofKeepsUncertainSubmitIdentityForRecovery() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            transport.close();
            assertThrows(ExecutionException.class, () -> submitted.get(1, TimeUnit.SECONDS));
            assertEquals(ClientState.DISCONNECTED, client.state());
            assertEquals(request.requestId(), client.submitRequestId().orElseThrow());
            assertTrue(client.submitRequestSent());
            assertFalse(client.submitRetryAvailable());
            assertEquals(0, client.pendingRequestCount());
        }
    }

    @Test
    void socketEofAfterFinalDoesNotEraseSubmission() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            transport.receiveAndAwait(submitAck(request, "SESSION-001"));
            ExamSubmission manual = submitted.get(1, TimeUnit.SECONDS);
            CompletableFuture<ProtocolMessage> pending = client.sendRequest(client.createJoinRequest("CAND-001", "EXAM-001"));
            transport.takeWritten();
            transport.close();
            assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
            assertEquals(ClientState.SUBMITTED, client.state());
            assertFalse(client.isConnected());
            assertEquals(manual, client.submission().orElseThrow());
        }
    }

    @Test
    void closeDuringSubmitReleasesFutureButRetainsLogicalIdentity() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ExamClient client = activeClient(transport, 1_000)) {
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            ProtocolMessage request = transport.takeWritten();
            client.close();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> submitted.get(1, TimeUnit.SECONDS));
            assertEquals(ClientState.CLOSED, client.state());
            assertEquals(request.requestId(), client.submitRequestId().orElseThrow());
            assertEquals(0, client.pendingRequestCount());
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
                        SUBMITTED_AT
                ))
        );
    }

    private static ProtocolMessage submitAck(ProtocolMessage request, String sessionId) {
        return ProtocolMessage.response(
                SUBMIT_ACK,
                request,
                PayloadCodec.toMap(new Payloads.SubmitAck(
                        sessionId, "SUBMITTED", SUBMITTED_AT
                ))
        );
    }

    private static ProtocolMessage autoSubmit(String sessionId) {
        return ProtocolMessage.push(
                AUTO_SUBMIT,
                PayloadCodec.toMap(new Payloads.AutoSubmit(
                        sessionId, "AUTO_SUBMITTED", SUBMITTED_AT
                ))
        );
    }

    private static final class FakeTransport implements ProtocolTransport {
        private static final Object EOF = new Object();
        private final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private final BlockingQueue<ProtocolMessage> written = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private CompletableFuture<Void> previousDelivery;

        @Override
        public ProtocolMessage read() throws IOException {
            try {
                if (previousDelivery != null) {
                    previousDelivery.complete(null);
                }
                Object next = incoming.take();
                if (next == EOF) {
                    return null;
                }
                Delivery delivery = (Delivery) next;
                previousDelivery = delivery.processed();
                return delivery.message();
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
            incoming.add(new Delivery(message, new CompletableFuture<>()));
        }

        void receiveAndAwait(ProtocolMessage message) throws Exception {
            CompletableFuture<Void> processed = new CompletableFuture<>();
            incoming.add(new Delivery(message, processed));
            processed.get(1, TimeUnit.SECONDS);
        }

        ProtocolMessage takeWritten() throws InterruptedException {
            return written.poll(1, TimeUnit.SECONDS);
        }

        ProtocolMessage pollWritten(long timeoutMs) throws InterruptedException {
            return written.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        private record Delivery(ProtocolMessage message, CompletableFuture<Void> processed) {
        }
    }
}
