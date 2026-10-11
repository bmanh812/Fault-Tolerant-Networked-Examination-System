package vn.edu.exam.server;

import org.junit.jupiter.api.Test;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.PayloadCodec;
import vn.edu.exam.common.protocol.Payloads;
import vn.edu.exam.common.protocol.ProtocolMessage;
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
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ServerSubmissionIntegrationTest {
    @Test
    void submitOverTcpReturnsSameAckForRetryAfterCommit() throws Exception {
        try (Fixture fixture = fixture()) {
            try (ExamClient client = fixture.connectClient()) {
                String sessionId = client.joinExam("CAND-001", "EXAM-001")
                        .get(3, TimeUnit.SECONDS).sessionId();
                ProtocolMessage wrongSession = ProtocolMessage.request(
                        MessageType.SUBMIT,
                        PayloadCodec.toMap(new Payloads.SubmitRequest("another-session"))
                );
                ProtocolMessage rejected = client.sendRequest(wrongSession).get(3, TimeUnit.SECONDS);
                assertEquals(MessageType.ERROR, rejected.type());
                assertEquals("INVALID_SESSION", rejected.payload().get("code"));
                ProtocolMessage request = ProtocolMessage.request(
                        MessageType.SUBMIT,
                        PayloadCodec.toMap(new Payloads.SubmitRequest(sessionId))
                );

                ProtocolMessage first = client.sendRequest(request).get(3, TimeUnit.SECONDS);
                ProtocolMessage repeated = client.sendRequest(request).get(3, TimeUnit.SECONDS);

                assertEquals(MessageType.SUBMIT_ACK, first.type());
                assertEquals(request.requestId(), first.requestId());
                assertEquals(first.payload(), repeated.payload());
                assertEquals("SUBMITTED", first.payload().get("status"));
                assertEquals("SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            }
        }
    }

    @Test
    void connectedClientReceivesAutoSubmitPushWithNewRequestId() throws Exception {
        try (Fixture fixture = fixture()) {
            CompletableFuture<ProtocolMessage> pushed = new CompletableFuture<>();
            try (ExamClient client = fixture.connectClient()) {
                client.setServerPushHandler(pushed::complete);
                String sessionId = client.joinExam("CAND-001", "EXAM-001")
                        .get(3, TimeUnit.SECONDS).sessionId();
                ProtocolMessage answer = ProtocolMessage.request(
                        MessageType.ANSWER_UPDATE,
                        PayloadCodec.toMap(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A"))
                );
                assertEquals(MessageType.ANSWER_ACK, client.sendRequest(answer).get(3, TimeUnit.SECONDS).type());

                try (Connection connection = fixture.database.open();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE exam_session SET deadline = ? WHERE session_id = ?"
                     )) {
                    statement.setLong(1, System.currentTimeMillis() - 1);
                    statement.setString(2, sessionId);
                    statement.executeUpdate();
                }
                fixture.submissions.sweepExpired();

                ProtocolMessage event = pushed.get(3, TimeUnit.SECONDS);
                assertEquals(MessageType.AUTO_SUBMIT, event.type());
                assertEquals(sessionId, event.payload().get("sessionId"));
                assertEquals("AUTO_SUBMITTED", event.payload().get("status"));
                assertNotEquals(answer.requestId(), event.requestId());
                assertEquals("AUTO_SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            }
        }
    }

    @Test
    void scheduledDeadlineAutoSubmitsWithoutAnotherClientRequest() throws Exception {
        try (Fixture fixture = fixture()) {
            try (Connection connection = fixture.database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE exam SET end_time = ? WHERE exam_id = 'EXAM-001'"
                 )) {
                statement.setLong(1, System.currentTimeMillis() + 1500);
                statement.executeUpdate();
            }
            CompletableFuture<ProtocolMessage> pushed = new CompletableFuture<>();
            try (ExamClient client = fixture.connectClient()) {
                client.setServerPushHandler(pushed::complete);
                String sessionId = client.joinExam("CAND-001", "EXAM-001")
                        .get(3, TimeUnit.SECONDS).sessionId();

                ProtocolMessage event = pushed.get(4, TimeUnit.SECONDS);
                assertEquals(MessageType.AUTO_SUBMIT, event.type());
                assertEquals(sessionId, event.payload().get("sessionId"));
                assertEquals("AUTO_SUBMITTED", fixture.repository.findById(sessionId).orElseThrow().status().name());
            }
        }
    }

    private static Fixture fixture() throws Exception {
        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:mem:tcp_submit_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        database.initialize(Path.of("data", "schema.sql"));
        database.seed(Path.of("data", "sample-data.sql"));
        Clock clock = Clock.systemUTC();
        H2SessionRepository repository = new H2SessionRepository(database, clock);
        DeadlineScheduler scheduler = new DeadlineScheduler();
        SessionConnectionRegistry connections = new SessionConnectionRegistry();
        SubmissionService submissions = new SubmissionService(repository, scheduler, connections::broadcast);
        ServerSocket listener = new ServerSocket(0);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> handled = CompletableFuture.runAsync(() -> {
            try {
                Socket socket = listener.accept();
                new ClientHandler(socket, 65_536,
                        new SessionService(repository, clock), new AnswerService(repository, clock),
                        submissions, connections).run();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }, worker);
        return new Fixture(database, repository, scheduler, submissions, connections, listener, worker, handled);
    }

    private record Fixture(
            DatabaseManager database,
            H2SessionRepository repository,
            DeadlineScheduler scheduler,
            SubmissionService submissions,
            SessionConnectionRegistry connections,
            ServerSocket listener,
            ExecutorService worker,
            CompletableFuture<Void> handled
    ) implements AutoCloseable {
        ExamClient connectClient() throws Exception {
            ExamClient client = new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, 65_536));
            client.connect("127.0.0.1", listener.getLocalPort());
            return client;
        }

        @Override
        public void close() throws Exception {
            listener.close();
            scheduler.close();
            connections.close();
            worker.shutdownNow();
            handled.get(3, TimeUnit.SECONDS);
        }
    }
}
