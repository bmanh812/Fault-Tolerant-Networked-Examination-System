package vn.edu.exam.server;

import org.junit.jupiter.api.Test;
import vn.edu.exam.client.AnswerDraft;
import vn.edu.exam.client.ClientExamState;
import vn.edu.exam.client.ClientTransportConfig;
import vn.edu.exam.client.ExamClient;
import vn.edu.exam.client.ui.AnswerDisplayStatus;
import vn.edu.exam.client.ui.ConnectionDisplayStatus;
import vn.edu.exam.client.ui.CoreExamClientGateway;
import vn.edu.exam.client.ui.ExamUiController;
import vn.edu.exam.client.ui.ExamUiView;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase6ClientUiIntegrationTest {
    @Test
    void controllerDrivesRealJoinAndAnswerFlowWithoutDuplicatingExamState() throws Exception {
        try (RunningServer server = RunningServer.start()) {
            ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
            ExamClient client = new ExamClient(new ClientTransportConfig(1_000, 100, 2_000, 65_536));
            SmokeView view = new SmokeView();
            ExamUiController controller = new ExamUiController(
                    new CoreExamClientGateway(client),
                    view,
                    networkExecutor,
                    Runnable::run
            );
            try {
                controller.join(
                        "127.0.0.1",
                        String.valueOf(server.port()),
                        "CAND-001",
                        "EXAM-001"
                );
                assertTrue(view.examShown.await(3, TimeUnit.SECONDS));
                assertNotNull(view.exam);
                assertEquals(3, view.exam.questions().size());
                assertTrue(view.exam.questions().stream()
                        .flatMap(question -> question.options().stream())
                        .noneMatch(option -> option.toString().contains("correct")));

                controller.selectAnswer("Q01", "Q01-A");
                assertTrue(view.answerSaved.await(3, TimeUnit.SECONDS));

                AnswerDraft clientAnswer = client.examState().answer("Q01");
                AnswerState databaseAnswer = server.repository
                        .findAnswers(view.exam.sessionId())
                        .get("Q01");
                assertEquals("Q01-A", clientAnswer.selectedOptionId());
                assertTrue(clientAnswer.isSaved());
                assertEquals("Q01-A", databaseAnswer.selectedOptionId());
                assertEquals(1, databaseAnswer.version());
                assertEquals(ConnectionDisplayStatus.CONNECTED, view.connectionStatus);
            } finally {
                controller.close();
            }
        }
    }

    private static final class SmokeView implements ExamUiView {
        private final CountDownLatch examShown = new CountDownLatch(1);
        private final CountDownLatch answerSaved = new CountDownLatch(1);
        private volatile ClientExamState.Snapshot exam;
        private volatile ConnectionDisplayStatus connectionStatus;

        @Override
        public void showJoinInProgress() {
        }

        @Override
        public void showJoinError(String message) {
        }

        @Override
        public void showExam(ClientExamState.Snapshot exam) {
            this.exam = exam;
            examShown.countDown();
        }

        @Override
        public void showAnswerState(String questionId, AnswerDraft answer, AnswerDisplayStatus status) {
            if (status == AnswerDisplayStatus.SAVED) {
                answerSaved.countDown();
            }
        }

        @Override
        public void showAnswerStatus(String questionId, AnswerDisplayStatus status) {
        }

        @Override
        public void showConnectionState(ConnectionDisplayStatus status) {
            connectionStatus = status;
        }

        @Override
        public void setAnsweringEnabled(boolean enabled) {
        }
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
                    "jdbc:h2:mem:phase6_" + UUID.randomUUID().toString().replace('-', '_')
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
