package vn.edu.exam.client;

import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.common.protocol.ProtocolValidator;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static vn.edu.exam.common.protocol.MessageType.JOIN_EXAM;
import static vn.edu.exam.common.protocol.MessageType.ANSWER_UPDATE;

public final class ExamClient implements AutoCloseable {
    private final AtomicReference<ClientState> state = new AtomicReference<>(ClientState.DISCONNECTED);
    private final ClientExamState examState = new ClientExamState();
    private final ClientTransportConfig config;
    private final TransportConnector connector;
    private final ScheduledExecutorService timeoutScheduler;
    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    /** Guarded by lifecycleLock; one network request at a time for each question. */
    private final Map<String, AnswerInFlight> answerUpdatesInFlight = new HashMap<>();
    /** Guarded by lifecycleLock; retained for the reconnect/resync phase. */
    private final Map<String, AnswerSyncIssue> answerSyncIssues = new HashMap<>();

    private volatile ProtocolTransport transport;
    private volatile Thread readerThread;
    private volatile Consumer<ProtocolMessage> serverPushHandler = message -> {
    };

    public ExamClient(ClientTransportConfig config) {
        this(config, (host, port, settings) -> SocketProtocolTransport.connect(
                host,
                port,
                settings.connectTimeoutMs(),
                settings.socketReadTimeoutMs(),
                settings.maxMessageBytes()
        ));
    }

    ExamClient(ClientTransportConfig config, TransportConnector connector) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.connector = Objects.requireNonNull(connector, "connector is required");
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "exam-client-request-timeouts");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host is required");
        synchronized (lifecycleLock) {
            if (state.get() == ClientState.CLOSED) {
                throw new IllegalStateException("Client is closed");
            }
            if (!state.compareAndSet(ClientState.DISCONNECTED, ClientState.CONNECTING)) {
                throw new IllegalStateException("Client must be disconnected before connect");
            }
        }

        ProtocolTransport opened;
        try {
            opened = connector.connect(host, port, config);
        } catch (IOException | RuntimeException error) {
            state.compareAndSet(ClientState.CONNECTING, ClientState.DISCONNECTED);
            throw error;
        }

        synchronized (lifecycleLock) {
            if (state.get() != ClientState.CONNECTING) {
                closeQuietly(opened);
                throw new IOException("Connection attempt was cancelled");
            }
            transport = opened;
            state.set(ClientState.CONNECTED);
            Thread thread = new Thread(() -> readerLoop(opened), "exam-client-reader");
            thread.setDaemon(true);
            readerThread = thread;
            thread.start();
        }
    }

    public CompletableFuture<ProtocolMessage> sendRequest(ProtocolMessage request) {
        Objects.requireNonNull(request, "request is required");
        ProtocolValidator.validateClientMessage(request);

        CompletableFuture<ProtocolMessage> future = new CompletableFuture<>();
        PendingRequest pending = new PendingRequest(request, future);
        ProtocolTransport activeTransport;
        IOException writeFailure = null;

        synchronized (lifecycleLock) {
            activeTransport = requireConnectedTransport();
            if (pendingRequests.putIfAbsent(request.requestId(), pending) != null) {
                throw new IllegalArgumentException("A request with this requestId is already pending");
            }
            future.whenComplete((response, error) -> {
                if (future.isCancelled() && pendingRequests.remove(request.requestId(), pending)) {
                    pending.cancelTimeout();
                }
            });

            ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(
                    () -> timeoutRequest(request.requestId()),
                    config.requestTimeoutMs(),
                    TimeUnit.MILLISECONDS
            );
            pending.setTimeoutTask(timeoutTask);

            try {
                activeTransport.write(request);
            } catch (IOException error) {
                writeFailure = error;
                completeExceptionally(request.requestId(), error);
            } catch (RuntimeException error) {
                completeExceptionally(request.requestId(), error);
                throw error;
            }
        }

        if (writeFailure != null) {
            terminateTransport(activeTransport, writeFailure);
        }
        return future;
    }

    public void setServerPushHandler(Consumer<ProtocolMessage> handler) {
        serverPushHandler = Objects.requireNonNull(handler, "handler is required");
    }

    public void disconnect() throws IOException {
        stop(ClientState.DISCONNECTED, new IOException("Client disconnected"), false);
    }

    public ProtocolMessage createJoinRequest(String candidateId, String examId) {
        return ProtocolMessage.request(
                JOIN_EXAM,
                PayloadCodec.toMap(new Payloads.JoinExamRequest(candidateId, examId))
        );
    }

    public CompletableFuture<ClientExamState.Snapshot> joinExam(String candidateId, String examId) {
        ProtocolMessage request = createJoinRequest(candidateId, examId);
        ProtocolValidator.validateClientMessage(request);
        synchronized (lifecycleLock) {
            if (!state.compareAndSet(ClientState.CONNECTED, ClientState.JOINING)) {
                throw new IllegalStateException("Client must be connected before joining an exam");
            }
        }

        CompletableFuture<ProtocolMessage> responseFuture;
        try {
            responseFuture = sendRequest(request);
        } catch (RuntimeException error) {
            restoreConnectedAfterJoinFailure();
            throw error;
        }

        return responseFuture
                .thenApply(response -> applyJoinResponse(request, examId, response))
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        restoreConnectedAfterJoinFailure();
                    }
                });
    }

    public ClientState state() {
        return state.get();
    }

    public ClientExamState examState() {
        return examState;
    }

    public AnswerDraft selectAnswer(String questionId, String optionId) {
        synchronized (lifecycleLock) {
            requireActiveExam();
            return examState.selectAnswer(questionId, optionId);
        }
    }

    /**
     * Selects an option locally and persists that logical change through ANSWER_UPDATE. If an older
     * version for the question is already in flight, this call waits for it and then sends the
     * newest required snapshot.
     */
    public CompletableFuture<AnswerDraft> updateAnswer(String questionId, String optionId) {
        int targetVersion;
        synchronized (lifecycleLock) {
            requireActiveExam();
            targetVersion = examState.selectAnswer(questionId, optionId).localVersion();
        }
        return ensureAnswerConfirmed(questionId, targetVersion);
    }

    /**
     * Sends the current unsaved answer. Calling this again after a timeout retries the immutable
     * pending snapshot with the same requestId, version and selectedOptionId.
     */
    public CompletableFuture<AnswerDraft> sendAnswerUpdate(String questionId) {
        int targetVersion;
        synchronized (lifecycleLock) {
            requireActiveExam();
            targetVersion = examState.answer(questionId).localVersion();
        }
        return ensureAnswerConfirmed(questionId, targetVersion);
    }

    public Optional<AnswerSyncIssue> answerSyncIssue(String questionId) {
        synchronized (lifecycleLock) {
            return Optional.ofNullable(answerSyncIssues.get(questionId));
        }
    }

    public boolean answerUpdateInFlight(String questionId) {
        synchronized (lifecycleLock) {
            return answerUpdatesInFlight.containsKey(questionId);
        }
    }

    /** Prepares local immutable data without performing network I/O. */
    public AnswerDraft.UpdateSnapshot prepareAnswerUpdate(String questionId) {
        synchronized (lifecycleLock) {
            requireActiveExam();
            return examState.prepareAnswerUpdate(questionId);
        }
    }

    public AnswerDraft.UpdateSnapshot retryAnswerUpdate(String questionId) {
        synchronized (lifecycleLock) {
            requireActiveExam();
            return examState.retryAnswerUpdate(questionId);
        }
    }

    private CompletableFuture<AnswerDraft> ensureAnswerConfirmed(String questionId, int targetVersion) {
        CompletableFuture<AnswerDraft> currentStep;
        synchronized (lifecycleLock) {
            if (state.get() != ClientState.ACTIVE) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Client must have an active exam")
                );
            }

            AnswerDraft answer = examState.answer(questionId);
            if (answer.confirmedVersion() >= targetVersion) {
                return CompletableFuture.completedFuture(answer);
            }

            AnswerSyncIssue syncIssue = answerSyncIssues.get(questionId);
            if (syncIssue != null && syncIssue.requiresResync()) {
                return CompletableFuture.failedFuture(new AnswerUpdateException(
                        syncIssue.errorCode(),
                        syncIssue.questionId(),
                        syncIssue.message(),
                        syncIssue.serverAnswer()
                ));
            }

            AnswerInFlight inFlight = answerUpdatesInFlight.get(questionId);
            if (inFlight == null) {
                inFlight = startAnswerUpdateLocked(questionId, answer);
            }
            currentStep = inFlight.completion();
        }

        return currentStep.thenCompose(updated -> {
            if (updated.confirmedVersion() >= targetVersion) {
                return CompletableFuture.completedFuture(updated);
            }
            return ensureAnswerConfirmed(questionId, targetVersion);
        });
    }

    private AnswerInFlight startAnswerUpdateLocked(String questionId, AnswerDraft answer) {
        AnswerDraft.UpdateSnapshot pendingSnapshot = answer.pendingSnapshot();
        final AnswerDraft.UpdateSnapshot snapshot = pendingSnapshot == null
                ? examState.prepareAnswerUpdate(questionId)
                : pendingSnapshot;

        ProtocolMessage request = new ProtocolMessage(
                ANSWER_UPDATE,
                ProtocolMessage.VERSION_1,
                snapshot.requestId(),
                PayloadCodec.toMap(new Payloads.AnswerUpdate(
                        snapshot.sessionId(),
                        snapshot.questionId(),
                        snapshot.version(),
                        snapshot.selectedOptionId()
                ))
        );
        ProtocolValidator.validateClientMessage(request);

        CompletableFuture<AnswerDraft> completion = new CompletableFuture<>();
        AnswerInFlight inFlight = new AnswerInFlight(snapshot, completion);
        answerUpdatesInFlight.put(questionId, inFlight);

        CompletableFuture<AnswerDraft> networkResult;
        try {
            networkResult = sendRequest(request).thenApply(response ->
                    applyAnswerResponse(request, snapshot, response)
            );
        } catch (RuntimeException error) {
            answerUpdatesInFlight.remove(questionId, inFlight);
            completion.completeExceptionally(error);
            return inFlight;
        }

        networkResult.whenComplete((updated, error) -> {
            synchronized (lifecycleLock) {
                answerUpdatesInFlight.remove(questionId, inFlight);
            }
            if (error == null) {
                completion.complete(updated);
            } else {
                completion.completeExceptionally(unwrapCompletionFailure(error));
            }
        });
        return inFlight;
    }

    private AnswerDraft applyAnswerResponse(
            ProtocolMessage request,
            AnswerDraft.UpdateSnapshot snapshot,
            ProtocolMessage response
    ) {
        ProtocolValidator.validateResponseTo(request, response);
        if (response.type() == MessageType.ERROR) {
            Payloads.ErrorResponse error = PayloadCodec.fromMap(
                    response.payload(), Payloads.ErrorResponse.class
            );
            validateAnswerError(snapshot, error);
            AnswerUpdateException failure = new AnswerUpdateException(
                    error.code(), snapshot.questionId(), error.message(), error.currentAnswer()
            );
            if (failure.requiresResync()) {
                synchronized (lifecycleLock) {
                    answerSyncIssues.put(snapshot.questionId(), new AnswerSyncIssue(
                            error.code(), snapshot.questionId(), error.message(), error.currentAnswer()
                    ));
                }
            }
            throw failure;
        }

        Payloads.AnswerAck acknowledgement = PayloadCodec.fromMap(
                response.payload(), Payloads.AnswerAck.class
        );
        validateAnswerAcknowledgement(snapshot, acknowledgement);
        synchronized (lifecycleLock) {
            if (!Objects.equals(examState.sessionId(), snapshot.sessionId())) {
                throw new IllegalArgumentException("ANSWER_ACK belongs to another active session");
            }
            AnswerDraft updated = examState.acknowledgeAnswer(snapshot);
            answerSyncIssues.remove(snapshot.questionId());
            return updated;
        }
    }

    private static void validateAnswerAcknowledgement(
            AnswerDraft.UpdateSnapshot snapshot,
            Payloads.AnswerAck acknowledgement
    ) {
        if (!snapshot.sessionId().equals(acknowledgement.sessionId())) {
            throw new IllegalArgumentException("ANSWER_ACK sessionId does not match the request");
        }
        if (!snapshot.questionId().equals(acknowledgement.questionId())) {
            throw new IllegalArgumentException("ANSWER_ACK questionId does not match the request");
        }
        if (snapshot.version() != acknowledgement.confirmedVersion()) {
            throw new IllegalArgumentException("ANSWER_ACK confirmedVersion does not match the request");
        }
        if (!snapshot.selectedOptionId().equals(acknowledgement.selectedOptionId())) {
            throw new IllegalArgumentException("ANSWER_ACK selectedOptionId does not match the request");
        }
    }

    private static void validateAnswerError(
            AnswerDraft.UpdateSnapshot snapshot,
            Payloads.ErrorResponse error
    ) {
        if (error.code() != ErrorCode.VERSION_CONFLICT) {
            return;
        }
        Payloads.AnswerView current = Objects.requireNonNull(
                error.currentAnswer(), "VERSION_CONFLICT requires currentAnswer"
        );
        if (!snapshot.questionId().equals(current.questionId())) {
            throw new IllegalArgumentException("VERSION_CONFLICT currentAnswer belongs to another question");
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    int pendingRequestCount() {
        return pendingRequests.size();
    }

    boolean readerThreadAlive() {
        Thread thread = readerThread;
        return thread != null && thread.isAlive();
    }

    @Override
    public void close() throws IOException {
        stop(ClientState.CLOSED, new CancellationException("Client closed"), true);
    }

    private void readerLoop(ProtocolTransport activeTransport) {
        Throwable terminalFailure = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ProtocolMessage message;
                try {
                    message = activeTransport.read();
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (message == null) {
                    terminalFailure = new EOFException("Server closed the connection");
                    break;
                }
                dispatchIncoming(message);
            }
        } catch (IOException | RuntimeException error) {
            terminalFailure = error;
        } finally {
            if (terminalFailure != null) {
                terminateTransport(activeTransport, terminalFailure);
            }
            synchronized (lifecycleLock) {
                if (readerThread == Thread.currentThread()) {
                    readerThread = null;
                }
            }
        }
    }

    private void dispatchIncoming(ProtocolMessage message) {
        if (message.type() == MessageType.AUTO_SUBMIT) {
            try {
                ProtocolValidator.validateServerMessage(message);
                serverPushHandler.accept(message);
            } catch (RuntimeException ignored) {
                // A malformed push or handler failure must not terminate the socket reader.
            }
            return;
        }

        PendingRequest pending = pendingRequests.remove(message.requestId());
        if (pending == null) {
            return;
        }
        pending.cancelTimeout();
        try {
            ProtocolValidator.validateResponseTo(pending.request(), message);
            pending.future().complete(message);
        } catch (RuntimeException error) {
            pending.future().completeExceptionally(error);
        }
    }

    private ClientExamState.Snapshot applyJoinResponse(
            ProtocolMessage request,
            String requestedExamId,
            ProtocolMessage response
    ) {
        ProtocolValidator.validateResponseTo(request, response);
        if (response.type() == MessageType.ERROR) {
            Payloads.ErrorResponse error = PayloadCodec.fromMap(
                    response.payload(),
                    Payloads.ErrorResponse.class
            );
            throw joinFailure(error);
        }

        Payloads.ExamState received = PayloadCodec.fromMap(
                response.payload(),
                Payloads.ExamState.class
        );
        if (!requestedExamId.equals(received.examId())) {
            throw new IllegalArgumentException("EXAM_STATE examId does not match JOIN_EXAM request");
        }

        synchronized (lifecycleLock) {
            if (state.get() != ClientState.JOINING) {
                throw new CancellationException("JOIN_EXAM was cancelled before EXAM_STATE was applied");
            }
            examState.applyExamState(received);
            answerUpdatesInFlight.clear();
            answerSyncIssues.clear();
            state.set(ClientState.ACTIVE);
            return examState.snapshot();
        }
    }

    private void restoreConnectedAfterJoinFailure() {
        synchronized (lifecycleLock) {
            state.compareAndSet(ClientState.JOINING, ClientState.CONNECTED);
        }
    }

    private static ExamJoinException joinFailure(Payloads.ErrorResponse error) {
        String summary = switch (error.code()) {
            case CANDIDATE_NOT_ALLOWED -> "Candidate is not allowed to join this exam";
            case EXAM_NOT_STARTED -> "Exam has not started";
            case EXAM_ENDED -> "Exam has ended";
            case SERVER_BUSY -> "Server is busy; try again later";
            default -> "JOIN_EXAM failed";
        };
        return new ExamJoinException(
                error.code(),
                summary + ": " + error.message()
        );
    }

    private void timeoutRequest(String requestId) {
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.future().completeExceptionally(new TimeoutException(
                    "Request " + requestId + " timed out after " + config.requestTimeoutMs() + " ms"
            ));
        }
    }

    private void completeExceptionally(String requestId, Throwable error) {
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending != null) {
            pending.cancelTimeout();
            pending.future().completeExceptionally(error);
        }
    }

    private void failAllPending(Throwable error) {
        pendingRequests.forEach((requestId, pending) -> {
            if (pendingRequests.remove(requestId, pending)) {
                pending.cancelTimeout();
                pending.future().completeExceptionally(error);
            }
        });
    }

    private ProtocolTransport requireConnectedTransport() {
        ProtocolTransport current = transport;
        ClientState currentState = state.get();
        if (current == null || currentState == ClientState.DISCONNECTED
                || currentState == ClientState.CONNECTING
                || currentState == ClientState.RECONNECTING
                || currentState == ClientState.CLOSED) {
            throw new IllegalStateException("Client is not connected");
        }
        return current;
    }

    private void requireActiveExam() {
        if (state.get() != ClientState.ACTIVE) {
            throw new IllegalStateException("Client must have an active exam");
        }
    }

    private void terminateTransport(ProtocolTransport failedTransport, Throwable error) {
        Thread threadToStop;
        synchronized (lifecycleLock) {
            if (transport != failedTransport) {
                return;
            }
            transport = null;
            if (state.get() != ClientState.CLOSED) {
                state.set(ClientState.DISCONNECTED);
            }
            threadToStop = readerThread;
        }

        closeQuietly(failedTransport);
        failAllPending(error);
        if (threadToStop != null && threadToStop != Thread.currentThread()) {
            threadToStop.interrupt();
        }
    }

    private void stop(ClientState targetState, Throwable pendingFailure, boolean shutdownScheduler) throws IOException {
        ProtocolTransport current;
        Thread threadToStop;
        synchronized (lifecycleLock) {
            if (targetState == ClientState.CLOSED && state.get() == ClientState.CLOSED) {
                return;
            }
            if (targetState == ClientState.DISCONNECTED && state.get() == ClientState.CLOSED) {
                return;
            }
            if (targetState == ClientState.DISCONNECTED && state.get() == ClientState.DISCONNECTED) {
                return;
            }
            state.set(targetState);
            current = transport;
            transport = null;
            threadToStop = readerThread;
        }

        IOException closeFailure = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException error) {
                closeFailure = error;
            }
        }
        failAllPending(pendingFailure);
        if (threadToStop != null && threadToStop != Thread.currentThread()) {
            threadToStop.interrupt();
            try {
                threadToStop.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                IOException interrupted = new IOException("Interrupted while stopping reader thread", error);
                if (closeFailure == null) {
                    closeFailure = interrupted;
                } else {
                    closeFailure.addSuppressed(interrupted);
                }
            }
        }
        if (shutdownScheduler) {
            timeoutScheduler.shutdownNow();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private static void closeQuietly(ProtocolTransport transport) {
        try {
            transport.close();
        } catch (IOException ignored) {
            // The original transport failure is more useful to pending callers.
        }
    }

    @FunctionalInterface
    interface TransportConnector {
        ProtocolTransport connect(String host, int port, ClientTransportConfig config) throws IOException;
    }

    private static final class PendingRequest {
        private final ProtocolMessage request;
        private final CompletableFuture<ProtocolMessage> future;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingRequest(ProtocolMessage request, CompletableFuture<ProtocolMessage> future) {
            this.request = request;
            this.future = future;
        }

        ProtocolMessage request() {
            return request;
        }

        CompletableFuture<ProtocolMessage> future() {
            return future;
        }

        void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
            this.timeoutTask = timeoutTask;
            if (future.isDone()) {
                timeoutTask.cancel(false);
            }
        }

        void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private record AnswerInFlight(
            AnswerDraft.UpdateSnapshot snapshot,
            CompletableFuture<AnswerDraft> completion
    ) {
    }
}
