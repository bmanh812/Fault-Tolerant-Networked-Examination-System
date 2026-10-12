package vn.edu.exam.server;

import org.junit.jupiter.api.Test;
import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.AnswerSyncIssue;
import vn.edu.exam.client.AnswerUpdateException;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.server.model.AnswerState;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.repository.H2SessionRepository;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.SessionService;
import vn.edu.exam.server.service.SubmissionService;
import vn.edu.exam.server.timer.DeadlineScheduler;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase5AnswerFlowIntegrationTest {
    @Test
    void firstAndChangedAnswersArePersistedBeforeClientBecomesSaved() throws Exception {
        try (RunningServer server = RunningServer.start();
             ExamClient client = server.newClient()) {
            client.connect("127.0.0.1", server.port());
            String sessionId = client.joinExam("CAND-001", "EXAM-001")
                    .get(3, TimeUnit.SECONDS).sessionId();

            AnswerDraft first = client.updateAnswer("Q01", "Q01-A").get(3, TimeUnit.SECONDS);
            AnswerState firstPersisted = server.repository.findAnswers(sessionId).get("Q01");
            assertEquals(1, first.confirmedVersion());
            assertTrue(first.isSaved());
            assertEquals(1, firstPersisted.version());
            assertEquals("Q01-A", firstPersisted.selectedOptionId());

            AnswerDraft changed = client.updateAnswer("Q01", "Q01-B").get(3, TimeUnit.SECONDS);
            AnswerState changedPersisted = server.repository.findAnswers(sessionId).get("Q01");
            assertEquals(2, changed.localVersion());
            assertEquals(2, changed.confirmedVersion());
            assertEquals("Q01-B", changed.selectedOptionId());
            assertTrue(changed.isSaved());
            assertEquals(2, changedPersisted.version());
            assertEquals("Q01-B", changedPersisted.selectedOptionId());
        }
    }

    @Test
    void exactDuplicateRequestIsIdempotentAcrossTcpServerAndClientState() throws Exception {
        try (RunningServer server = RunningServer.start();
             ExamClient client = server.newClient()) {
            client.connect("127.0.0.1", server.port());
            String sessionId = client.joinExam("CAND-001", "EXAM-001")
                    .get(3, TimeUnit.SECONDS).sessionId();

            client.selectAnswer("Q01", "Q01-A");
            AnswerDraft.UpdateSnapshot snapshot = client.prepareAnswerUpdate("Q01");
            AnswerDraft saved = client.sendAnswerUpdate("Q01").get(3, TimeUnit.SECONDS);

            ProtocolMessage duplicate = answerRequest(snapshot);
            ProtocolMessage duplicateAck = client.sendRequest(duplicate).get(3, TimeUnit.SECONDS);
            Payloads.AnswerAck ack = PayloadCodec.fromMap(
                    duplicateAck.payload(), Payloads.AnswerAck.class
            );
            AnswerDraft afterDuplicate = client.examState().acknowledgeAnswer(snapshot);
            AnswerState persisted = server.repository.findAnswers(sessionId).get("Q01");

            assertEquals(MessageType.ANSWER_ACK, duplicateAck.type());
            assertEquals(snapshot.requestId(), duplicateAck.requestId());
            assertEquals(1, ack.confirmedVersion());
            assertSame(saved, afterDuplicate);
            assertEquals(1, persisted.version());
            assertEquals("Q01-A", persisted.selectedOptionId());
        }
    }

    @Test
    void realVersionConflictKeepsLocalSelectionAndExposesAuthoritativeState() throws Exception {
        try (RunningServer server = RunningServer.start();
             ExamClient client = server.newClient()) {
            client.connect("127.0.0.1", server.port());
            String sessionId = client.joinExam("CAND-001", "EXAM-001")
                    .get(3, TimeUnit.SECONDS).sessionId();

            ProtocolMessage authoritative = ProtocolMessage.request(
                    MessageType.ANSWER_UPDATE,
                    PayloadCodec.toMap(new Payloads.AnswerUpdate(
                            sessionId, "Q01", 1, "Q01-A"
                    ))
            );
            assertEquals(
                    MessageType.ANSWER_ACK,
                    client.sendRequest(authoritative).get(3, TimeUnit.SECONDS).type()
            );

            CompletableFuture<AnswerDraft> conflicting = client.updateAnswer("Q01", "Q01-B");
            ExecutionException failed = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> conflicting.get(3, TimeUnit.SECONDS)
            );
            AnswerUpdateException conflict = assertInstanceOf(
                    AnswerUpdateException.class, failed.getCause()
            );

            AnswerDraft local = client.examState().answer("Q01");
            AnswerSyncIssue issue = client.answerSyncIssue("Q01").orElseThrow();
            Map<String, AnswerState> persisted = server.repository.findAnswers(sessionId);

            assertTrue(conflict.requiresResync());
            assertEquals("Q01-B", local.selectedOptionId());
            assertEquals(1, local.localVersion());
            assertEquals(0, local.confirmedVersion());
            assertFalse(local.isSaved());
            assertEquals("Q01-A", issue.serverAnswer().selectedOptionId());
            assertEquals("Q01-A", persisted.get("Q01").selectedOptionId());
            assertEquals(1, persisted.get("Q01").version());
        }
    }

    private static ProtocolMessage answerRequest(AnswerDraft.UpdateSnapshot snapshot) {
        return new ProtocolMessage(
                MessageType.ANSWER_UPDATE,
                ProtocolMessage.VERSION_1,
                snapshot.requestId(),
                PayloadCodec.toMap(new Payloads.AnswerUpdate(
                        snapshot.sessionId(),
                        snapshot.questionId(),
                        snapshot.version(),
                        snapshot.selectedOptionId()
                ))
        );
    }

    private static final class RunningServer implements AutoCloseable {
        private final H2SessionRepository repository;
        private final ServerSocket listener;
        private final SessionConnectionRegistry connections;
        private final DeadlineScheduler scheduler;
        private final ExecutorService serverThread;
        private final CompletableFuture<Void> handled;

        private RunningServer(
                H2SessionRepository repository,
                ServerSocket listener,
                SessionConnectionRegistry connections,
                DeadlineScheduler scheduler,
                ExecutorService serverThread,
                CompletableFuture<Void> handled
        ) {
            this.repository = repository;
            this.listener = listener;
            this.connections = connections;
            this.scheduler = scheduler;
            this.serverThread = serverThread;
            this.handled = handled;
        }

        static RunningServer start() throws Exception {
            DatabaseManager database = new DatabaseManager(
                    "jdbc:h2:mem:phase5_" + UUID.randomUUID().toString().replace('-', '_')
                            + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
            );
            database.initialize(Path.of("data", "schema.sql"));
            database.seed(Path.of("data", "sample-data.sql"));
            H2SessionRepository repository = new H2SessionRepository(database);
            Clock clock = Clock.systemUTC();
            ServerSocket listener = new ServerSocket(0);
            SessionConnectionRegistry connections = new SessionConnectionRegistry();
            DeadlineScheduler scheduler = new DeadlineScheduler();
            SubmissionService submissions = new SubmissionService(
                    repository, scheduler, connections::broadcast
            );
            ExecutorService serverThread = Executors.newSingleThreadExecutor();
            CompletableFuture<Void> handled = CompletableFuture.runAsync(() -> {
                try {
                    Socket socket = listener.accept();
                    new ClientHandler(
                            socket,
                            65_536,
                            new SessionService(repository, clock),
                            new AnswerService(repository, clock),
                            submissions,
                            connections
                    ).run();
                } catch (Exception error) {
                    if (!listener.isClosed()) {
                        throw new RuntimeException(error);
                    }
                }
            }, serverThread);
            return new RunningServer(
                    repository, listener, connections, scheduler, serverThread, handled
            );
        }

        int port() {
            return listener.getLocalPort();
        }

        ExamClient newClient() {
            return new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, 65_536));
        }

        @Override
        public void close() throws Exception {
            listener.close();
            connections.close();
            scheduler.close();
            try {
                handled.get(3, TimeUnit.SECONDS);
            } finally {
                serverThread.shutdownNow();
                serverThread.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }
}
