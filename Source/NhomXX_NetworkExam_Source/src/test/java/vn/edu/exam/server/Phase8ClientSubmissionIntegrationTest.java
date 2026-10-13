package vn.edu.exam.server;

import org.junit.jupiter.api.Test;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.client.ExamSubmission;
import vn.edu.exam.client.Phase8TestClient;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.server.repository.SessionRepository;
import vn.edu.exam.server.model.ExamSession;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase8ClientSubmissionIntegrationTest {
    @Test
    void realClientFlushesAnswerThenManualSubmitPersistsOneFinalSubmission() throws Exception {
        try (Fixture fixture = Fixture.start();
             ExamClient client = fixture.connectClient()) {
            String sessionId = client.joinExam("CAND-001", "EXAM-001")
                    .get(3, TimeUnit.SECONDS).sessionId();

            // Deliberately leave the local answer unsent; submit must flush it first.
            client.selectAnswer("Q01", "Q01-B");
            ExamSubmission submission = client.submitExam().get(3, TimeUnit.SECONDS);

            AnswerState persisted = fixture.repository.findAnswers(sessionId).get("Q01");
            assertEquals(ClientState.SUBMITTED, submission.state());
            assertEquals(ClientState.SUBMITTED, client.state());
            assertEquals("Q01-B", persisted.selectedOptionId());
            assertEquals(1, persisted.version());
            assertEquals("SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertEquals("MANUAL", fixture.submitType(sessionId));
            assertThrows(IllegalStateException.class, () -> client.updateAnswer("Q01", "Q01-A"));

            // The Client-side duplicate call is idempotent and never creates another row.
            ExamSubmission duplicate = client.submitExam().get(1, TimeUnit.SECONDS);
            assertEquals(submission, duplicate);
            assertEquals(1, fixture.submissionCount(sessionId));
        }
    }

    @Test
    void repeatedLogicalSubmitOverTcpReturnsSameAckAndOneDatabaseRow() throws Exception {
        try (Fixture fixture = Fixture.start(); ExamClient client = fixture.connectClient()) {
            String sessionId = client.joinExam("CAND-001", "EXAM-001").get(3, TimeUnit.SECONDS).sessionId();
            ProtocolMessage request = ProtocolMessage.request(MessageType.SUBMIT,
                    PayloadCodec.toMap(new Payloads.SubmitRequest(sessionId)));
            ProtocolMessage first = client.sendRequest(request).get(3, TimeUnit.SECONDS);
            ProtocolMessage retry = client.sendRequest(request).get(3, TimeUnit.SECONDS);
            assertEquals(first, retry);
            assertEquals(request.requestId(), retry.requestId());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertEquals("MANUAL", fixture.submitType(sessionId));
            ProtocolMessage lateAnswer = ProtocolMessage.request(MessageType.ANSWER_UPDATE,
                    PayloadCodec.toMap(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A")));
            ProtocolMessage rejected = client.sendRequest(lateAnswer).get(3, TimeUnit.SECONDS);
            assertEquals(MessageType.ERROR, rejected.type());
            assertEquals("ALREADY_SUBMITTED", rejected.payload().get("code"));
            assertTrue(fixture.repository.findAnswers(sessionId).isEmpty());
        }
    }

    @Test
    void realInflightAnswerMustCommitBeforeSubmitAndCannotCreateVersionFour() throws Exception {
        try (Fixture fixture = Fixture.start(true); ExamClient client = fixture.connectClient()) {
            String sessionId = client.joinExam("CAND-001", "EXAM-001").get(3, TimeUnit.SECONDS).sessionId();
            client.selectAnswer("Q01", "Q01-A");
            client.selectAnswer("Q01", "Q01-B");
            CompletableFuture<?> update = client.updateAnswer("Q01", "Q01-C");
            assertTrue(fixture.answerEntered.await(3, TimeUnit.SECONDS));
            CompletableFuture<ExamSubmission> submitted = client.submitExam();
            assertEquals(ClientState.SUBMITTING, client.state());
            assertThrows(IllegalStateException.class, () -> client.updateAnswer("Q01", "Q01-D"));
            assertEquals(0, fixture.submissionCount(sessionId));
            assertEquals(3, client.examState().answer("Q01").localVersion());
            fixture.releaseAnswer.countDown();
            update.get(3, TimeUnit.SECONDS);
            submitted.get(3, TimeUnit.SECONDS);
            AnswerState persisted = fixture.repository.findAnswers(sessionId).get("Q01");
            assertEquals(3, persisted.version());
            assertEquals("Q01-C", persisted.selectedOptionId());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertEquals(ClientState.SUBMITTED, client.state());
        }
    }

    @Test
    void lostAckAfterRealCommitRetriesSameLogicalSubmitWithoutSecondSubmission() throws Exception {
        LinkedBlockingQueue<ProtocolMessage> writes = new LinkedBlockingQueue<>();
        try (Fixture fixture = Fixture.start();
             ExamClient client = Phase8TestClient.droppingFirstSubmitAck(writes::add)) {
            client.connect("127.0.0.1", fixture.listener.getLocalPort());
            String sessionId = client.joinExam("CAND-001", "EXAM-001").get(3, TimeUnit.SECONDS).sessionId();
            writes.clear();
            CompletableFuture<ExamSubmission> first = client.submitExam();
            ProtocolMessage original = writes.poll(1, TimeUnit.SECONDS);
            ExecutionException timeout = assertThrows(ExecutionException.class,
                    () -> first.get(3, TimeUnit.SECONDS));
            assertTrue(timeout.getCause() instanceof TimeoutException);
            assertEquals(ClientState.SUBMITTING, client.state());
            assertEquals("SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertTrue(client.submission().isEmpty());
            CompletableFuture<ExamSubmission> retry = client.submitExam();
            ProtocolMessage repeated = writes.poll(1, TimeUnit.SECONDS);
            assertEquals(original, repeated);
            assertEquals(ClientState.SUBMITTED, retry.get(3, TimeUnit.SECONDS).state());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertEquals("MANUAL", fixture.submitType(sessionId));
        }
    }

    @Test
    void realAutoSubmitPushFinalizesOnlineClientAndLocksAnswers() throws Exception {
        try (Fixture fixture = Fixture.start();
             ExamClient client = fixture.connectClient()) {
            CompletableFuture<ExamSubmission> pushed = new CompletableFuture<>();
            client.setSubmissionHandler(pushed::complete);
            String sessionId = client.joinExam("CAND-001", "EXAM-001")
                    .get(3, TimeUnit.SECONDS).sessionId();

            fixture.expire(sessionId);
            fixture.submissions.sweepExpired();

            ExamSubmission submission = pushed.get(3, TimeUnit.SECONDS);
            assertTrue(submission.autoSubmitted());
            assertEquals(ClientState.AUTO_SUBMITTED, client.state());
            assertEquals("AUTO_SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            assertEquals(1, fixture.submissionCount(sessionId));
            assertEquals("AUTO", fixture.submitType(sessionId));
            assertThrows(IllegalStateException.class, () -> client.selectAnswer("Q01", "Q01-A"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DatabaseManager database;
        private final H2SessionRepository repository;
        private final DeadlineScheduler scheduler;
        private final SubmissionService submissions;
        private final SessionConnectionRegistry connections;
        private final ServerSocket listener;
        private final ExecutorService worker;
        private final CompletableFuture<Void> handled;
        private final CountDownLatch answerEntered;
        private final CountDownLatch releaseAnswer;

        private Fixture(
                DatabaseManager database,
                H2SessionRepository repository,
                DeadlineScheduler scheduler,
                SubmissionService submissions,
                SessionConnectionRegistry connections,
                ServerSocket listener,
                ExecutorService worker,
                CompletableFuture<Void> handled,
                CountDownLatch answerEntered,
                CountDownLatch releaseAnswer
        ) {
            this.database = database;
            this.repository = repository;
            this.scheduler = scheduler;
            this.submissions = submissions;
            this.connections = connections;
            this.listener = listener;
            this.worker = worker;
            this.handled = handled;
            this.answerEntered = answerEntered;
            this.releaseAnswer = releaseAnswer;
        }

        private static Fixture start() throws Exception {
            return start(false);
        }

        private static Fixture start(boolean gateAnswer) throws Exception {
            DatabaseManager database = new DatabaseManager(
                    "jdbc:h2:mem:phase8_" + UUID.randomUUID().toString().replace('-', '_')
                            + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
            );
            database.initialize(Path.of("data", "schema.sql"));
            database.seed(Path.of("data", "sample-data.sql"));
            Clock clock = Clock.systemUTC();
            H2SessionRepository repository = new H2SessionRepository(database, clock);
            CountDownLatch answerEntered = new CountDownLatch(1);
            CountDownLatch releaseAnswer = new CountDownLatch(gateAnswer ? 1 : 0);
            SessionRepository answers = new GatedAnswers(repository, answerEntered, releaseAnswer);
            DeadlineScheduler scheduler = new DeadlineScheduler();
            SessionConnectionRegistry connections = new SessionConnectionRegistry();
            SubmissionService submissions = new SubmissionService(
                    repository, scheduler, connections::broadcast
            );
            ServerSocket listener = new ServerSocket(0);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            CompletableFuture<Void> handled = CompletableFuture.runAsync(() -> {
                try {
                    Socket socket = listener.accept();
                    new ClientHandler(
                            socket,
                            65_536,
                            new SessionService(repository, clock),
                            new AnswerService(answers, clock),
                            submissions,
                            connections
                    ).run();
                } catch (Exception error) {
                    if (!listener.isClosed()) {
                        throw new RuntimeException(error);
                    }
                }
            }, worker);
            return new Fixture(
                    database, repository, scheduler, submissions,
                    connections, listener, worker, handled, answerEntered, releaseAnswer
            );
        }

        private ExamClient connectClient() throws Exception {
            ExamClient client = new ExamClient(
                    new ClientTransportConfig(1_000, 100, 2_000, 65_536)
            );
            client.connect("127.0.0.1", listener.getLocalPort());
            return client;
        }

        private void expire(String sessionId) throws Exception {
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE exam_session SET deadline = ? WHERE session_id = ?"
                 )) {
                statement.setLong(1, System.currentTimeMillis() - 1);
                statement.setString(2, sessionId);
                statement.executeUpdate();
            }
        }

        private int submissionCount(String sessionId) throws Exception {
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM submission WHERE session_id = ?"
                 )) {
                statement.setString(1, sessionId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }

        private String submitType(String sessionId) throws Exception {
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT submit_type FROM submission WHERE session_id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        }

        @Override
        public void close() throws Exception {
            releaseAnswer.countDown();
            listener.close();
            scheduler.close();
            connections.close();
            worker.shutdownNow();
            handled.get(3, TimeUnit.SECONDS);
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            try (Connection connection = database.open(); java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    /** Test-only gate before the real H2 write; production business logic is unchanged. */
    private record GatedAnswers(
            SessionRepository delegate, CountDownLatch entered, CountDownLatch release
    ) implements SessionRepository {
        @Override
        public AnswerState saveAnswerIfNewer(Payloads.AnswerUpdate update) {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the answer gate");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return delegate.saveAnswerIfNewer(update);
        }

        @Override public JoinedExam joinExam(String candidate, String exam) { return delegate.joinExam(candidate, exam); }
        @Override public Optional<ExamSession> findById(String id) { return delegate.findById(id); }
        @Override public Map<String, AnswerState> findAnswers(String id) { return delegate.findAnswers(id); }
        @Override public FinalizedSession submit(String id) { return delegate.submit(id); }
        @Override public Optional<FinalizedSession> autoSubmitIfExpired(String id) { return delegate.autoSubmitIfExpired(id); }
        @Override public List<String> findExpiredSessionIds() { return delegate.findExpiredSessionIds(); }
        @Override public Optional<FinalizedSession> findFinalized(String id) { return delegate.findFinalized(id); }
    }
}
