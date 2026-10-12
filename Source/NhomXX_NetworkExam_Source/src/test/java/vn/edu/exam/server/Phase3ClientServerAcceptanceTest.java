package vn.edu.exam.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientState;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.client.ExamJoinException;
import vn.edu.exam.common.protocol.ErrorCode;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.repository.H2SessionRepository;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.SessionService;
import vn.edu.exam.server.service.SubmissionService;
import vn.edu.exam.server.timer.DeadlineScheduler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase3ClientServerAcceptanceTest {
    private static final int MAX_MESSAGE_BYTES = 65_536;

    @Test
    void joinOverRealTcpAppliesCompleteExamStateWithoutLeakingCorrectAnswers() throws Exception {
        try (RunningServer server = RunningServer.start()) {
            ClientExamState.Snapshot snapshot;
            try (ExamClient client = server.newClient()) {
                client.connect("127.0.0.1", server.port());
                snapshot = client.joinExam("CAND-001", "EXAM-001").get(3, TimeUnit.SECONDS);

                assertEquals(ClientState.ACTIVE, client.state());
                assertEquals("EXAM-001", snapshot.examId());
                assertNotNull(snapshot.sessionId());
                UUID.fromString(snapshot.sessionId());
                assertEquals("ACTIVE", snapshot.sessionStatus());
                assertEquals(3, snapshot.questions().size());
                assertTrue(snapshot.serverTime() > 0);
                assertTrue(snapshot.deadline() > snapshot.serverTime());

                Map<String, ClientExamState.Question> questions = snapshot.questions().stream()
                        .collect(Collectors.toMap(ClientExamState.Question::questionId, question -> question));
                assertEquals(Set.of("Q01", "Q02", "Q03"), questions.keySet());
                assertEquals("TCP cung cap loai dich vu nao?", questions.get("Q01").text());
                Map<String, ClientExamState.Option> q1Options = questions.get("Q01").options().stream()
                        .collect(Collectors.toMap(ClientExamState.Option::optionId, option -> option));
                assertEquals(Set.of("Q01-A", "Q01-B", "Q01-C", "Q01-D"), q1Options.keySet());
                assertEquals("Byte stream", q1Options.get("Q01-A").text());
                assertEquals("Message datagram", q1Options.get("Q01-B").text());
            }

            try (RawClient raw = server.newRawClient()) {
                ProtocolMessage request = ProtocolMessage.request(
                        MessageType.JOIN_EXAM,
                        PayloadCodec.toMap(new Payloads.JoinExamRequest("CAND-001", "EXAM-001"))
                );
                raw.protocol().write(request);
                ProtocolMessage response = raw.protocol().read();

                assertEquals(MessageType.EXAM_STATE, response.type());
                assertEquals(request.requestId(), response.requestId());
                String wirePayload = new ObjectMapper().writeValueAsString(response.payload())
                        .toLowerCase(Locale.ROOT);
                assertFalse(wirePayload.contains("correctoptionid"));
                assertFalse(wirePayload.contains("correctanswer"));
                assertFalse(wirePayload.contains("iscorrect"));
            }
        }
    }

    @Test
    void joiningAgainRestoresPersistedAnswerIntoClientExamState() throws Exception {
        try (RunningServer server = RunningServer.start()) {
            String originalSessionId;
            try (ExamClient firstClient = server.newClient()) {
                firstClient.connect("127.0.0.1", server.port());
                originalSessionId = firstClient.joinExam("CAND-001", "EXAM-001")
                        .get(3, TimeUnit.SECONDS).sessionId();
                ProtocolMessage update = ProtocolMessage.request(
                        MessageType.ANSWER_UPDATE,
                        PayloadCodec.toMap(new Payloads.AnswerUpdate(
                                originalSessionId, "Q01", 1, "Q01-A"
                        ))
                );
                ProtocolMessage acknowledgement = firstClient.sendRequest(update).get(3, TimeUnit.SECONDS);
                assertEquals(MessageType.ANSWER_ACK, acknowledgement.type());
                assertEquals(update.requestId(), acknowledgement.requestId());
            }

            try (ExamClient secondClient = server.newClient()) {
                secondClient.connect("127.0.0.1", server.port());
                ClientExamState.Snapshot restored = secondClient.joinExam("CAND-001", "EXAM-001")
                        .get(3, TimeUnit.SECONDS);

                assertEquals(originalSessionId, restored.sessionId());
                AnswerDraft restoredAnswer = restored.answers().get("Q01");
                assertEquals("Q01-A", restoredAnswer.selectedOptionId());
                assertEquals(1, restoredAnswer.localVersion());
                assertEquals(1, restoredAnswer.confirmedVersion());
                assertTrue(restoredAnswer.isConfirmed());
                assertEquals(ClientState.ACTIVE, secondClient.state());
            }
        }
    }

    @Test
    void unknownCandidateReturnsCorrelatedErrorAndKeepsConnectionUsable() throws Exception {
        assertJoinFailure("CAND-MISSING", "EXAM-001", ErrorCode.CANDIDATE_NOT_ALLOWED, ignored -> {
        });
    }

    @Test
    void futureExamReturnsCorrelatedErrorAndKeepsConnectionUsable() throws Exception {
        assertJoinFailure("CAND-001", "EXAM-FUTURE", ErrorCode.EXAM_NOT_STARTED, server -> {
            long now = System.currentTimeMillis();
            server.insertExam("EXAM-FUTURE", now + 60_000, now + 120_000, "SCHEDULED");
        });
    }

    @Test
    void endedExamReturnsCorrelatedErrorAndKeepsConnectionUsable() throws Exception {
        assertJoinFailure("CAND-001", "EXAM-ENDED", ErrorCode.EXAM_ENDED, server -> {
            long now = System.currentTimeMillis();
            server.insertExam("EXAM-ENDED", now - 120_000, now - 60_000, "ENDED");
        });
    }

    @Test
    void unknownExamReturnsCorrelatedErrorAndKeepsConnectionUsable() throws Exception {
        assertJoinFailure("CAND-001", "EXAM-MISSING", ErrorCode.INVALID_REQUEST, ignored -> {
        });
    }

    private static void assertJoinFailure(
            String candidateId,
            String examId,
            ErrorCode expectedCode,
            Consumer<RunningServer> arrange
    ) throws Exception {
        try (RunningServer server = RunningServer.start()) {
            arrange.accept(server);

            try (RawClient raw = server.newRawClient()) {
                ProtocolMessage rejectedRequest = ProtocolMessage.request(
                        MessageType.JOIN_EXAM,
                        PayloadCodec.toMap(new Payloads.JoinExamRequest(candidateId, examId))
                );
                raw.protocol().write(rejectedRequest);
                ProtocolMessage error = raw.protocol().read();
                assertEquals(MessageType.ERROR, error.type());
                assertEquals(rejectedRequest.requestId(), error.requestId());
                assertEquals(expectedCode.name(), error.payload().get("code"));

                ProtocolMessage validRequest = ProtocolMessage.request(
                        MessageType.JOIN_EXAM,
                        PayloadCodec.toMap(new Payloads.JoinExamRequest("CAND-002", "EXAM-001"))
                );
                raw.protocol().write(validRequest);
                ProtocolMessage validResponse = raw.protocol().read();
                assertEquals(MessageType.EXAM_STATE, validResponse.type());
                assertEquals(validRequest.requestId(), validResponse.requestId());
            }

            try (ExamClient client = server.newClient()) {
                client.connect("127.0.0.1", server.port());
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> client.joinExam(candidateId, examId).get(3, TimeUnit.SECONDS)
                );
                ExamJoinException joinError = assertInstanceOf(ExamJoinException.class, failure.getCause());
                assertEquals(expectedCode, joinError.errorCode());
                assertEquals(ClientState.CONNECTED, client.state());
                assertTrue(client.examState().questions().isEmpty());
                assertNull(client.examState().sessionId());

                ClientExamState.Snapshot recovered = client.joinExam("CAND-003", "EXAM-001")
                        .get(3, TimeUnit.SECONDS);
                assertEquals("EXAM-001", recovered.examId());
                assertEquals(ClientState.ACTIVE, client.state());
            }
        }
    }

    private record RawClient(Socket socket, ProtocolIO protocol) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class RunningServer implements AutoCloseable {
        private final DatabaseManager database;
        private final ServerSocket listener;
        private final SessionConnectionRegistry connections;
        private final DeadlineScheduler scheduler;
        private final ExecutorService acceptor;
        private final ExecutorService handlers;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean running = new AtomicBoolean(true);

        private RunningServer(
                DatabaseManager database,
                ServerSocket listener,
                SessionConnectionRegistry connections,
                DeadlineScheduler scheduler,
                ExecutorService acceptor,
                ExecutorService handlers
        ) {
            this.database = database;
            this.listener = listener;
            this.connections = connections;
            this.scheduler = scheduler;
            this.acceptor = acceptor;
            this.handlers = handlers;
        }

        static RunningServer start() throws Exception {
            DatabaseManager database = new DatabaseManager(
                    "jdbc:h2:mem:phase3_" + UUID.randomUUID().toString().replace('-', '_')
                            + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
            );
            database.initialize(Path.of("data", "schema.sql"));
            database.seed(Path.of("data", "sample-data.sql"));
            ServerSocket listener = new ServerSocket(0);
            SessionConnectionRegistry connections = new SessionConnectionRegistry();
            DeadlineScheduler scheduler = new DeadlineScheduler();
            ExecutorService acceptor = Executors.newSingleThreadExecutor();
            ExecutorService handlers = Executors.newCachedThreadPool();
            RunningServer server = new RunningServer(
                    database, listener, connections, scheduler, acceptor, handlers
            );
            server.startAccepting();
            return server;
        }

        int port() {
            return listener.getLocalPort();
        }

        ExamClient newClient() {
            return new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, MAX_MESSAGE_BYTES));
        }

        RawClient newRawClient() throws IOException {
            Socket socket = new Socket("127.0.0.1", port());
            socket.setSoTimeout(2_000);
            return new RawClient(
                    socket,
                    new ProtocolIO(socket.getInputStream(), socket.getOutputStream(), MAX_MESSAGE_BYTES)
            );
        }

        void insertExam(String examId, long startTime, long endTime, String status) {
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO exam (exam_id, title, start_time, end_time, status) VALUES (?, ?, ?, ?, ?)"
                 )) {
                statement.setString(1, examId);
                statement.setString(2, "Isolated acceptance exam");
                statement.setLong(3, startTime);
                statement.setLong(4, endTime);
                statement.setString(5, status);
                statement.executeUpdate();
            } catch (Exception error) {
                throw new IllegalStateException("Could not arrange isolated exam", error);
            }
        }

        private void startAccepting() {
            H2SessionRepository repository = new H2SessionRepository(database);
            Clock clock = Clock.systemUTC();
            SubmissionService submissions = new SubmissionService(repository, scheduler, connections::broadcast);
            acceptor.execute(() -> {
                while (running.get()) {
                    try {
                        Socket socket = listener.accept();
                        socket.setSoTimeout(0);
                        sockets.add(socket);
                        handlers.execute(() -> {
                            try {
                                new ClientHandler(
                                        socket,
                                        MAX_MESSAGE_BYTES,
                                        new SessionService(repository, clock),
                                        new AnswerService(repository, clock),
                                        submissions,
                                        connections
                                ).run();
                            } finally {
                                sockets.remove(socket);
                            }
                        });
                    } catch (SocketException closed) {
                        if (running.get()) {
                            throw new IllegalStateException("Acceptance server socket failed", closed);
                        }
                    } catch (IOException error) {
                        throw new IllegalStateException("Acceptance server failed", error);
                    }
                }
            });
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            listener.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            connections.close();
            scheduler.close();
            handlers.shutdownNow();
            acceptor.shutdownNow();
            handlers.awaitTermination(3, TimeUnit.SECONDS);
            acceptor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
