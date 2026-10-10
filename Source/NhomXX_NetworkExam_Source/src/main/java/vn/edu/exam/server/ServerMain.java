package vn.edu.exam.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.exam.common.config.AppConfig;
import vn.edu.exam.server.persistence.DatabaseManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
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

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LOGGER.info("MCQ Exam Server listening on port {}", port);
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(readTimeout);
                try {
                    executor.execute(new ClientHandler(socket, maxMessageBytes));
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

