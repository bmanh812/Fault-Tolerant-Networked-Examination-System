package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.config.AppConfig;
import vn.edu.exam.server.persistence.DatabaseManager;
import vn.edu.exam.server.repository.H2SessionRepository;
import vn.edu.exam.server.repository.SessionRepository;
import vn.edu.exam.server.service.AnswerService;
import vn.edu.exam.server.service.SessionService;
import vn.edu.exam.server.service.SubmissionService;
import vn.edu.exam.server.timer.DeadlineScheduler;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ServerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load(Path.of("config", "application.properties"));
        DatabaseManager database = new DatabaseManager(
                config.get("database.url"),
                config.get("database.user"),
                config.get("database.password")
        );
        database.initialize(Path.of(config.get("database.schema")));
        Clock clock = Clock.systemUTC();
        SessionRepository repository = new H2SessionRepository(database, clock);
        SessionService sessions = new SessionService(repository, clock);
        AnswerService answers = new AnswerService(repository, clock);

        int port = config.getInt("server.port");
        int workers = config.getInt("server.workerThreads");
        int queueCapacity = config.getInt("server.queueCapacity");
        int readTimeout = config.getInt("server.socketReadTimeoutMs");
        int maxMessageBytes = config.getInt("protocol.maxMessageBytes");

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );

        try (SessionConnectionRegistry connections = new SessionConnectionRegistry();
             DeadlineScheduler scheduler = new DeadlineScheduler();
             ServerSocket serverSocket = new ServerSocket(port)) {
            SubmissionService submissions = new SubmissionService(repository, scheduler, connections::broadcast);
            submissions.startSweep(Duration.ofSeconds(1));
            LOGGER.info("MCQ Exam Server listening on port {}", port);
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(readTimeout);
                try {
                    executor.execute(new ClientHandler(
                            socket, maxMessageBytes, sessions, answers, submissions, connections
                    ));
                } catch (RuntimeException rejected) {
                    LOGGER.warn("Server busy; rejecting {}", socket.getRemoteSocketAddress());
                    socket.close();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }
}

