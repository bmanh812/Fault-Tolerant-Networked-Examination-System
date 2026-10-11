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
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerJoinAnswerIntegrationTest {
    @Test
    void realClientJoinsAndReceivesAckAfterAnswerIsPersisted() throws Exception {
        DatabaseManager database = new DatabaseManager(
                "jdbc:h2:mem:tcp_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        database.initialize(Path.of("data", "schema.sql"));
        database.seed(Path.of("data", "sample-data.sql"));
        H2SessionRepository repository = new H2SessionRepository(database);
        Clock clock = Clock.systemUTC();

        try (SessionConnectionRegistry connections = new SessionConnectionRegistry();
             ServerSocket listener = new ServerSocket(0);
             DeadlineScheduler scheduler = new DeadlineScheduler()) {
            SubmissionService submissions = new SubmissionService(repository, scheduler, connections::broadcast);
            ExecutorService serverThread = Executors.newSingleThreadExecutor();
            try {
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
                        throw new RuntimeException(error);
                    }
                }, serverThread);

                try (ExamClient client = new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, 65_536))) {
                    client.connect("127.0.0.1", listener.getLocalPort());
                    String sessionId = client.joinExam("CAND-001", "EXAM-001")
                            .get(3, TimeUnit.SECONDS).sessionId();
                    ProtocolMessage update = ProtocolMessage.request(
                            MessageType.ANSWER_UPDATE,
                            PayloadCodec.toMap(new Payloads.AnswerUpdate(sessionId, "Q01", 1, "Q01-A"))
                    );
                    ProtocolMessage response = client.sendRequest(update).get(3, TimeUnit.SECONDS);

                    assertEquals(MessageType.ANSWER_ACK, response.type());
                    assertEquals(update.requestId(), response.requestId());
                    assertEquals(1, ((Number) response.payload().get("confirmedVersion")).intValue());
                    assertEquals("Q01-A", repository.findAnswers(sessionId).get("Q01").selectedOptionId());
                }
                handled.get(3, TimeUnit.SECONDS);
            } finally {
                serverThread.shutdownNow();
            }
        }
    }
}
